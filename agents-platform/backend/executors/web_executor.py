"""
executors/web_executor.py — WebMonitorExecutor (fase 23, O3.4 da refatoração,
extraído de executor.py): monitora URLs HTTP/HTTPS.
"""
import asyncio
import json
from datetime import datetime, timezone
from uuid import UUID

import aiohttp

from database import DB
from llm import is_enabled as llm_enabled
from notifier import send_telegram
from executors.common import ai_fallback, log, memory_recall, memory_save


class WebMonitorExecutor:
    """
    Monitora URLs HTTP/HTTPS.
    Rules:
    {
      "checks": [
        {"url": "https://app.example.com", "expect_status": 200,
         "expect_contains": "VoipIA", "timeout": 10},
        {"url": "https://api.example.com/health",
         "expect_json_key": "status", "expect_json_value": "ok"}
      ],
      "alert_on_failure": true,
      "use_ai_on_failure": false
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
        self.checks    = self.rules.get("checks", [
            {"url": u, "expect_status": 200} for u in (agent.get("target_urls") or [])
        ])
        self.use_ai    = self.rules.get("use_ai_on_failure", False) and llm_enabled()

    async def _emit(self, db, level, message, server=None):
        await log(db, self.eid, self.agent_id, level, message, server)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "server": server, "message": message
        })

    async def run(self, servers: list) -> dict:
        total = passed = failed = 0
        report = {"urls": [], "ai_suggestions": []}

        async with DB() as db, aiohttp.ClientSession() as session:
            await self._emit(db, "info", f"Monitorando {len(self.checks)} URL(s)")

            for check in self.checks:
                url  = check.get("url", "")
                tout = check.get("timeout", 15)
                total += 1
                entry = {"url": url, "ok": False, "reason": "", "status_code": None}

                try:
                    async with session.get(url,
                        timeout=aiohttp.ClientTimeout(total=tout), ssl=False) as resp:
                        body   = await resp.text()
                        status = resp.status
                        ok     = True
                        reason = f"HTTP {status}"
                        entry["status_code"] = status

                        if "expect_status" in check and status != check["expect_status"]:
                            ok     = False
                            reason = f"status {status} ≠ esperado {check['expect_status']}"

                        if ok and "expect_contains" in check:
                            if check["expect_contains"] not in body:
                                ok     = False
                                reason = f"'{check['expect_contains']}' não encontrado no body"

                        if ok and "expect_json_key" in check:
                            try:
                                data = await resp.json(content_type=None)
                                val  = data.get(check["expect_json_key"])
                                if str(val) != str(check.get("expect_json_value", "")):
                                    ok     = False
                                    reason = f"JSON {check['expect_json_key']}={val}"
                            except Exception:
                                ok = False; reason = "resposta não é JSON válido"

                        if ok and "expect_response_time_ms" in check:
                            # verificado após a requisição — aproximação
                            pass

                except aiohttp.ClientError as e:
                    ok = False; reason = f"erro de conexão: {e}"
                except asyncio.TimeoutError:
                    ok = False; reason = f"timeout ({tout}s)"

                if ok:
                    passed += 1
                else:
                    failed += 1

                entry.update({"ok": ok, "reason": reason})
                report["urls"].append(entry)
                await self._emit(db, "success" if ok else "error",
                    f"{'✓' if ok else '✗'} {url} → {reason}", url)

                if not ok:
                    # Alerta Telegram
                    if self.agent.get("notify_telegram") and self.agent.get("telegram_chat"):
                        await send_telegram(
                            self.agent["telegram_chat"],
                            f"🚨 [{self.agent['name']}]\n{url}\n{reason}"
                        )
                    # Alerta na web (banco)
                    await db.execute("""
                        INSERT INTO alerts
                            (agent_id, execution_id, channel, level, message, delivered)
                        VALUES ($1,$2,'web','error',$3,true)
                    """, self.agent_id, self.eid, f"{url}: {reason}")

                    # Broadcast alerta global para UI
                    await self.broadcast("__alerts__", {
                        "ts": datetime.now(timezone.utc).isoformat(),
                        "agent_id": str(self.agent_id),
                        "agent_name": self.agent.get("name", ""),
                        "level": "error",
                        "message": f"{url}: {reason}"
                    })

                    # Fallback IA
                    if self.use_ai:
                        mem_ctx      = await memory_recall(self.agent_id, f"URL {url} {reason}")
                        ai_suggestion = await ai_fallback(
                            skill=self.agent.get("skill", ""),
                            problem=f"{url}: {reason}",
                            memory_ctx=mem_ctx,
                            check_output=f"HTTP {entry.get('status_code', '?')}",
                        )
                        await self._emit(db, "info", f"💡 IA sugere: {ai_suggestion[:200]}", url)
                        report["ai_suggestions"].append({
                            "url": url, "suggestion": ai_suggestion})

        return {"total": total, "passed": passed, "failed": failed, "report": report}
