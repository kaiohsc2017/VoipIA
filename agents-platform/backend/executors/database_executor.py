"""
executors/database_executor.py — DatabaseExecutor (fase 23, O3.4 da refatoração,
extraído de executor.py): executa queries SQL de verificação contra bancos remotos.
"""
import asyncio
import json
import re
from datetime import datetime, timezone
from uuid import UUID

from database import DB
from executors.common import ai_fallback, log, memory_recall


class DatabaseExecutor:
    """
    Executa queries SQL de verificação contra bancos de dados remotos.
    Suporta PostgreSQL via asyncpg.

    Exemplo de rules:
      {"checks": [
        {"name": "Chamadas com falha",
         "dsn": "postgresql://user:pass@host/db",
         "query": "SELECT COUNT(*) FROM calls WHERE status='failed' AND created_at > NOW()-INTERVAL '1h'",
         "expect_lt": 10,
         "fix_hint": "Verificar trunk SIP"}
      ]}
    """
    def __init__(self, agent: dict, execution_id: UUID, broadcast):
        self.agent        = agent
        self.execution_id = execution_id
        self.agent_id     = UUID(str(agent["id"]))
        self.broadcast    = broadcast
        rules             = agent.get("rules") or {}
        if isinstance(rules, str):
            rules = json.loads(rules)
        self.checks  = rules.get("checks", [])
        self.use_ai  = rules.get("use_ai_on_failure", False)
        self.timeout = rules.get("timeout", 30)

    async def _emit(self, db, level, message, server=None, raw=None):
        await log(db, self.execution_id, self.agent_id, level, message, server, raw)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "message": message, "server": server or ""
        })

    def _evaluate(self, check: dict, value) -> tuple[bool, str]:
        try:
            num = float(value)
        except (TypeError, ValueError):
            num = None
        if "expect_eq"   in check: ok = str(value) == str(check["expect_eq"]);  return ok, f"{value} {'==' if ok else '!='} {check['expect_eq']}"
        if "expect_lt"   in check and num is not None: ok = num < float(check["expect_lt"]); return ok, f"{num} {'<' if ok else '>='} {check['expect_lt']}"
        if "expect_gt"   in check and num is not None: ok = num > float(check["expect_gt"]); return ok, f"{num} {'>' if ok else '<='} {check['expect_gt']}"
        if "expect_zero" in check: ok = num == 0; return ok, "zero" if ok else f"{num} (esperado zero)"
        return True, str(value)

    async def run(self, servers: list[dict]) -> dict:
        total = passed = failed = 0
        report = {"checks": [], "ai_suggestions": []}
        async with DB() as db:
            await self._emit(db, "info", f"DatabaseExecutor: {len(self.checks)} verificação(ões)")
            for check in self.checks:
                total += 1
                name  = check.get("name", check.get("query", "query")[:40])
                dsn   = check.get("dsn", "")
                query = check.get("query", "")
                if not dsn or not query:
                    await self._emit(db, "error", f"✗ {name}: DSN ou query ausente")
                    failed += 1
                    report["checks"].append({"name": name, "ok": False, "reason": "DSN/query ausente"})
                    continue
                # Achado de segurança: o caminho de sucesso já sanitiza a DSN antes
                # de logar (só host, nunca usuário/senha) — o except genérico abaixo
                # não tinha a mesma cautela, e str(e) do asyncpg pode ecoar a DSN
                # inteira (ex: erro de parsing de connection string).
                safe_dsn = dsn.split("@")[-1].split("/")[0] if "@" in dsn else dsn[:30]
                try:
                    import asyncpg as _pg
                    conn = await asyncio.wait_for(_pg.connect(dsn), timeout=self.timeout)
                    try:
                        row   = await asyncio.wait_for(conn.fetchrow(query), timeout=self.timeout)
                        value = row[0] if row else None
                    finally:
                        await conn.close()
                    ok, reason = self._evaluate(check, value)
                    fix = check.get("fix_hint", "") if not ok else ""
                    msg = f"{'✓' if ok else '✗'} {name}: {reason}"
                    if fix: msg += f" → {fix}"
                    await self._emit(db, "success" if ok else "error", msg, safe_dsn)
                    if ok: passed += 1
                    else:
                        failed += 1
                        if self.use_ai and not fix:
                            s = await ai_fallback(skill=self.agent.get("skill",""),
                                problem=f"{name}: {reason}",
                                memory_ctx=await memory_recall(self.agent_id, f"{name} {reason}"),
                                check_output=str(value))
                            await self._emit(db, "info", f"💡 IA sugere: {s[:200]}")
                            fix = s
                    report["checks"].append({"name": name, "ok": ok, "reason": reason,
                                              "fix_hint": fix, "value": str(value)})
                except asyncio.TimeoutError:
                    await self._emit(db, "error", f"✗ {name}: timeout ({self.timeout}s)")
                    failed += 1; report["checks"].append({"name": name, "ok": False, "reason": "timeout"})
                except Exception as e:
                    # Achado da revisão: o replace() por substring exata não pega o caso em
                    # que o asyncpg decodifica (urllib.parse.unquote) usuário/senha da DSN
                    # antes de ecoar no erro — a forma decodificada não bate com a DSN
                    # original char a char. Regex incondicional em qualquer "user:pass@"
                    # cobre a DSN original E variações decodificadas/reserializadas.
                    err_msg = re.sub(r'://[^:@/\s]+:[^@/\s]+@', '://***:***@', str(e))
                    await self._emit(db, "error", f"✗ {name}: {err_msg}")
                    failed += 1; report["checks"].append({"name": name, "ok": False, "reason": err_msg})
        return {"total": total, "passed": passed, "failed": failed, "report": report}
