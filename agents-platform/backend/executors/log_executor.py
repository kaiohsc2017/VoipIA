"""
executors/log_executor.py — LogMonitorExecutor (fase 23, O3.4 da refatoração,
extraído de executor.py): monitora arquivos de log via SSH em servidores remotos.
"""
import asyncio
import json
import shlex
from datetime import datetime, timezone
from uuid import UUID

import asyncssh

from database import DB
from llm import is_enabled as llm_enabled
from notifier import send_telegram
from executors.common import _build_ssh_kwargs, ai_fallback, log, memory_recall, memory_save


class LogMonitorExecutor:
    """
    Monitora arquivos de log via SSH em servidores remotos.
    Rules:
    {
      "log_checks": [
        {
          "name": "erros nginx",
          "file": "/var/log/nginx/error.log",
          "pattern": "ERROR|CRIT|emerg",
          "alert_if_found": true,
          "lines": 100,
          "fix_hint": "Verificar configuração nginx: nginx -t"
        },
        {
          "name": "asterisk warnings",
          "file": "/var/log/asterisk/messages",
          "pattern": "WARNING|ERROR",
          "alert_if_found": true,
          "lines": 200,
          "min_occurrences": 5
        },
        {
          "name": "disco crítico",
          "file": "/var/log/syslog",
          "pattern": "No space left",
          "alert_if_found": true,
          "lines": 50
        }
      ],
      "timeout_per_check": 30,
      "use_ai_on_failure": true
    }
    """
    def __init__(self, agent, execution_id, broadcast):
        self.agent     = agent
        self.eid       = execution_id
        self.agent_id  = UUID(str(agent["id"]))
        self.broadcast = broadcast
        rules = agent.get("rules") or {}
        # rules pode chegar como string JSON crua do banco (asyncpg não decodifica
        # jsonb automaticamente sem um codec registrado) — mesmo achado emergente
        # já corrigido em DatabaseExecutor e no cálculo de no_targets em run_agent.
        if isinstance(rules, str):
            rules = json.loads(rules)
        self.rules     = rules
        self.checks    = self.rules.get("log_checks", [])
        self.timeout   = self.rules.get("timeout_per_check", 30)
        self.use_ai    = self.rules.get("use_ai_on_failure", False) and llm_enabled()

    async def _emit(self, db, level, message, server=None, raw=None):
        await log(db, self.eid, self.agent_id, level, message, server, raw)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "server": server, "message": message
        })

    async def _connect(self, server: dict):
        return await asyncssh.connect(**_build_ssh_kwargs(server))

    async def run(self, servers: list[dict]) -> dict:
        total = passed = failed = 0
        report = {"servers": [], "ai_suggestions": []}

        async with DB() as db:
            await self._emit(db, "info",
                f"Monitor de logs: {len(servers)} servidor(es), {len(self.checks)} arquivo(s)")

            for srv in servers:
                srv_report = {"server": srv["name"], "checks": []}
                await self._emit(db, "info",
                    f"Conectando a {srv['name']}", srv["name"])

                try:
                    async with await self._connect(srv) as conn:
                        await self._emit(db, "success", "SSH conectado", srv["name"])

                        for check in self.checks:
                            total += 1
                            name     = check.get("name", check.get("file", "log"))
                            filepath = check.get("file", "")
                            pattern  = check.get("pattern", "ERROR")
                            lines    = check.get("lines", 100)
                            min_occ  = check.get("min_occurrences", 1)
                            alert_if = check.get("alert_if_found", True)
                            fix      = check.get("fix_hint", "")

                            # Valida filepath e pattern antes de interpolar no shell
                            safe_lines   = max(1, min(int(lines), 10000))
                            safe_filepath = shlex.quote(filepath)
                            safe_pattern  = shlex.quote(pattern)
                            cmd = (f"tail -n {safe_lines} {safe_filepath} 2>/dev/null | "
                                   f"grep -iE {safe_pattern} 2>/dev/null || true")

                            try:
                                result = await asyncio.wait_for(
                                    conn.run(cmd), timeout=self.timeout)
                                matches = [l for l in result.stdout.splitlines() if l.strip()]
                                count   = len(matches)
                                raw_out = "\n".join(matches[:20])  # máx 20 linhas no relatório
                            except asyncio.TimeoutError:
                                await self._emit(db, "warning",
                                    f"⚠ {name}: timeout ({self.timeout}s)", srv["name"])
                                failed += 1
                                continue

                            # Avalia resultado
                            found = count >= min_occ
                            # alert_if_found=True → falha SE encontrou (ex: padrão de erro)
                            # alert_if_found=False → falha SE NÃO encontrou (ex: heartbeat)
                            ok = not found if alert_if else found

                            level = "success" if ok else "error"
                            if ok:
                                passed += 1
                                await self._emit(db, "success",
                                    f"✓ {name}: {'nenhuma ocorrência' if alert_if else f'{count} ocorrência(s)'}",
                                    srv["name"])
                            else:
                                failed += 1
                                msg = (f"✗ {name}: {count} ocorrência(s) de '{pattern}' "
                                       f"em {filepath}")
                                if fix:
                                    msg += f" → {fix}"
                                await self._emit(db, "error", msg, srv["name"], raw_out)

                                # Alerta Telegram
                                if self.agent.get("notify_telegram") and self.agent.get("telegram_chat"):
                                    await send_telegram(
                                        self.agent["telegram_chat"],
                                        f"🚨 [{self.agent['name']}] {srv['name']}\n"
                                        f"{name}: {count} ocorrência(s)\n"
                                        f"Trecho:\n{raw_out[:300]}"
                                    )

                                # Alerta web
                                await db.execute("""
                                    INSERT INTO alerts
                                        (agent_id,execution_id,channel,level,message,delivered)
                                    VALUES ($1,$2,'web','error',$3,true)
                                """, self.agent_id, self.eid,
                                    f"{srv['name']} | {name}: {count} ocorrência(s)")

                                # Broadcast alerta global
                                await self.broadcast("__alerts__", {
                                    "ts": datetime.now(timezone.utc).isoformat(),
                                    "agent_id": str(self.agent_id),
                                    "agent_name": self.agent.get("name", ""),
                                    "level": "error",
                                    "message": f"{srv['name']}: {name} — {count} ocorrência(s)"
                                })

                                # Fallback IA se não há fix_hint
                                if self.use_ai and not fix:
                                    mem_ctx = await memory_recall(
                                        self.agent_id,
                                        f"log {pattern} {name} {raw_out[:200]}")
                                    ai_suggestion = await ai_fallback(
                                        skill=self.agent.get("skill", ""),
                                        problem=f"{name}: {count} ocorrência(s) de '{pattern}'",
                                        memory_ctx=mem_ctx,
                                        check_output=raw_out,
                                    )
                                    await self._emit(db, "info",
                                        f"💡 IA sugere: {ai_suggestion[:200]}", srv["name"])
                                    await memory_save(self.agent_id, "fix",
                                        f"IA log: {name}", ai_suggestion,
                                        {"server": srv["name"], "pattern": pattern})
                                    report["ai_suggestions"].append({
                                        "check": name, "server": srv["name"],
                                        "suggestion": ai_suggestion
                                    })
                                    fix = ai_suggestion

                            srv_report["checks"].append({
                                "name": name, "ok": ok,
                                "occurrences": count, "pattern": pattern,
                                "file": filepath, "fix_hint": fix,
                                "sample": raw_out[:500]
                            })

                except (asyncssh.Error, OSError, ConnectionError) as e:
                    failed += 1
                    total  += 1
                    await self._emit(db, "error", f"Falha SSH: {e}", srv["name"])
                    srv_report["error"] = str(e)
                except (KeyError, TypeError, ValueError) as e:
                    failed += 1
                    total  += 1
                    await self._emit(db, "error",
                        f"Erro ao avaliar check (configuração inválida): {e}", srv["name"])
                    srv_report["error"] = str(e)

                report["servers"].append(srv_report)

        return {"total": total, "passed": passed, "failed": failed, "report": report}
