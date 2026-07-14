"""
executors/ssh_executor.py — SSHTestExecutor (fase 23, O3.4 da refatoração,
extraído de executor.py): testa servidores via SSH com verificações configuráveis.
"""
import asyncio
import json
import re
from datetime import datetime, timezone
from uuid import UUID

import asyncssh

from database import DB
from llm import is_enabled as llm_enabled
from executors.common import _build_ssh_kwargs, ai_fallback, log, memory_recall, memory_save


class SSHTestExecutor:
    """
    Testa servidores via SSH com verificações configuráveis.
    Rules (JSONB no campo rules do agente):
    {
      "checks": [
        {"name": "nginx rodando", "cmd": "systemctl is-active nginx",
         "expect": "active", "fix_hint": "sudo systemctl restart nginx"},
        {"name": "disco < 80%", "cmd": "df / | awk 'NR==2{print $5}'",
         "expect_lt": "80%"},
        {"name": "porta 80 aberta", "cmd": "ss -tlnp | grep :80",
         "expect_contains": ":80"},
        {"name": "script ok", "cmd": "/opt/check.sh", "expect_exit": 0}
      ],
      "timeout_per_check": 30,
      "stop_on_first_failure": false,
      "use_ai_on_failure": false
    }
    """
    def __init__(self, agent: dict, execution_id: UUID, broadcast):
        self.agent        = agent
        self.eid          = execution_id
        self.agent_id     = UUID(str(agent["id"]))
        self.broadcast    = broadcast
        rules = agent.get("rules") or {}
        # rules pode chegar como string JSON crua do banco (asyncpg não decodifica
        # jsonb automaticamente sem um codec registrado) — mesmo achado emergente
        # já corrigido em DatabaseExecutor e no cálculo de no_targets em run_agent.
        if isinstance(rules, str):
            rules = json.loads(rules)
        self.rules        = rules
        self.checks       = self.rules.get("checks", [])
        self.timeout      = self.rules.get("timeout_per_check", 30)
        self.stop_on_fail = self.rules.get("stop_on_first_failure", False)
        self.use_ai       = self.rules.get("use_ai_on_failure", False) and llm_enabled()

    async def _emit(self, db, level, message, server=None, raw=None):
        await log(db, self.eid, self.agent_id, level, message, server, raw)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "server": server, "message": message
        })

    async def _connect(self, server: dict):
        return await asyncssh.connect(**_build_ssh_kwargs(server))

    def _evaluate(self, check: dict, stdout: str, exit_code: int) -> tuple[bool, str]:
        out = stdout.strip()
        if "expect" in check:
            ok = out == check["expect"]
            return ok, f"esperado '{check['expect']}', obtido '{out}'"
        if "expect_contains" in check:
            ok = check["expect_contains"] in out
            return ok, f"'{check['expect_contains']}' {'encontrado' if ok else 'NÃO encontrado'}"
        if "expect_exit" in check:
            ok = exit_code == check["expect_exit"]
            exp = check["expect_exit"]
            msg = "ok" if ok else f"esperado {exp}"
            return ok, f"exit code {exit_code} ({msg})"
        if "expect_lt" in check:
            try:
                val = int(re.sub(r"[^\d]", "", out))
                thr = int(re.sub(r"[^\d]", "", str(check["expect_lt"])))
                ok  = val < thr
                return ok, f"{val} {'<' if ok else '>='} {thr}"
            except Exception:
                return False, f"não foi possível comparar '{out}'"
        if "expect_regex" in check:
            ok = bool(re.search(check["expect_regex"], out))
            return ok, f"regex {'casou' if ok else 'não casou'}"
        return exit_code == 0, f"exit {exit_code}"

    async def run(self, servers: list[dict]) -> dict:
        total = passed = failed = 0
        report = {"servers": [], "ai_suggestions": []}

        async with DB() as db:
            await self._emit(db, "info",
                f"Iniciando {len(servers)} servidor(es), {len(self.checks)} verificação(ões)")

            for srv in servers:
                srv_report = {"server": srv["name"], "host": srv["host"], "checks": []}
                await self._emit(db, "info",
                    f"Conectando a {srv['name']} ({srv['host']}:{srv['port']})", srv["name"])

                try:
                    async with await self._connect(srv) as conn:
                        await self._emit(db, "success", "SSH conectado", srv["name"])

                        for check in self.checks:
                            total += 1
                            name = check.get("name", check.get("cmd", "check"))
                            try:
                                result = await asyncio.wait_for(
                                    conn.run(check["cmd"]), timeout=self.timeout)
                                ok, reason = self._evaluate(check, result.stdout, result.exit_status)
                                raw_out = result.stdout[:500]
                            except asyncio.TimeoutError:
                                ok, reason = False, f"timeout ({self.timeout}s)"
                                raw_out = ""

                            level = "success" if ok else "error"
                            if ok:
                                passed += 1
                            else:
                                failed += 1

                            fix = check.get("fix_hint", "") if not ok else ""
                            msg = f"{'✓' if ok else '✗'} {name}: {reason}"
                            if fix:
                                msg += f" → {fix}"
                            await self._emit(db, level, msg, srv["name"], raw_out)

                            # Fallback IA se falhou e não há fix_hint coberto
                            ai_suggestion = None
                            if not ok and self.use_ai and not fix:
                                mem_ctx = await memory_recall(
                                    self.agent_id, f"{name} {reason} {raw_out[:200]}")
                                ai_suggestion = await ai_fallback(
                                    skill=self.agent.get("skill", ""),
                                    problem=f"{name}: {reason}",
                                    memory_ctx=mem_ctx,
                                    check_output=raw_out,
                                )
                                await self._emit(db, "info",
                                    f"💡 IA sugere: {ai_suggestion[:200]}", srv["name"])
                                # Salva na memória para uso futuro
                                await memory_save(self.agent_id, "fix",
                                    f"IA: {name}", ai_suggestion,
                                    {"server": srv["name"], "check": name})
                                report["ai_suggestions"].append({
                                    "check": name, "server": srv["name"],
                                    "suggestion": ai_suggestion
                                })

                            srv_report["checks"].append({
                                "name": name, "ok": ok, "reason": reason,
                                "fix_hint": fix or ai_suggestion, "output": raw_out[:200]
                            })

                            if not ok and self.stop_on_fail:
                                break

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
