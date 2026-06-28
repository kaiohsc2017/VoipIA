"""
executor.py — Motor de execução dos agentes
Cada agente tem um executor especializado baseado no seu tipo.

Executors disponíveis:
  - SSHTestExecutor    : testes via SSH com verificações configuráveis
  - WebMonitorExecutor : monitoramento HTTP/HTTPS
  - LogMonitorExecutor : monitoramento de arquivos de log via SSH

Todos os executors:
  1. Tentam resolver com regras do script (sem IA)
  2. Se não coberto, consultam memória coletiva dos agentes
  3. Se ainda sem resposta, fazem fallback para Gemini com contexto completo
"""
import asyncio, json, re, aiohttp, asyncssh, os, logging, shlex
from datetime import datetime, timezone
from uuid import UUID, uuid4
from database import DB
from notifier import send_telegram, send_web_alert
from llm import ask as llm_ask, is_enabled as llm_enabled

logger = logging.getLogger("asteriskia.executor")

try:
    from croniter import croniter as _croniter  # noqa: F401
    HAS_CRONITER = True
except ImportError:
    HAS_CRONITER = False

_SSH_KNOWN_HOSTS = os.environ.get("SSH_KNOWN_HOSTS_FILE", "")


def _build_ssh_kwargs(server: dict) -> dict:
    """Monta kwargs para asyncssh.connect() centralizando host key checking."""
    kwargs: dict = dict(
        host=server["host"],
        port=server["port"],
        username=server["username"],
        known_hosts=_SSH_KNOWN_HOSTS or None,
        connect_timeout=15,
    )
    if not _SSH_KNOWN_HOSTS:
        logger.warning(
            "SSH sem verificação de host key para %s — defina SSH_KNOWN_HOSTS_FILE no .env",
            server["host"],
        )
    if server.get("auth_type") == "key" and server.get("ssh_key"):
        kwargs["client_keys"] = [asyncssh.import_private_key(server["ssh_key"])]
    else:
        kwargs["password"] = server.get("password", "")
    return kwargs

# ─── Helpers ──────────────────────────────────────────────────────────────────

async def log(db, execution_id: UUID, agent_id: UUID, level: str,
              message: str, server: str = None, raw: str = None):
    await db.execute("""
        INSERT INTO execution_logs (execution_id, agent_id, level, server, message, raw_output)
        VALUES ($1,$2,$3,$4,$5,$6)
    """, execution_id, agent_id, level, server, message, raw)

# ─── Memória + base de conhecimento ──────────────────────────────────────────

async def memory_recall(agent_id: UUID, query: str) -> str:
    """Busca memória própria + de outros agentes + base de conhecimento."""
    async with DB() as db:
        own = await db.fetch("""
            SELECT title, content, mtype FROM agent_memory
            WHERE agent_id=$1 AND (content % $2 OR title % $2)
            ORDER BY similarity(content,$2) DESC LIMIT 5
        """, agent_id, query)

        shared = await db.fetch("""
            SELECT am.title, am.content, am.mtype, a.name as agent_name
            FROM agent_memory am JOIN agents a ON a.id=am.agent_id
            WHERE am.agent_id!=$1 AND (am.content % $2 OR am.title % $2)
            ORDER BY am.usefulness DESC, similarity(am.content,$2) DESC LIMIT 3
        """, agent_id, query)

        docs = await db.fetch("""
            SELECT filename, LEFT(content,600) as excerpt
            FROM knowledge_docs WHERE content % $1
            ORDER BY similarity(content,$1) DESC LIMIT 2
        """, query)

    parts = []
    if own:
        parts.append("### Memória própria\n" + "\n".join(
            f"- [{r['mtype']}] {r['title']}: {r['content'][:200]}" for r in own))
    if shared:
        parts.append("### Memória de outros agentes\n" + "\n".join(
            f"- [{r['agent_name']}] {r['title']}: {r['content'][:200]}" for r in shared))
    if docs:
        parts.append("### Base de conhecimento\n" + "\n".join(
            f"- {r['filename']}: {r['excerpt']}" for r in docs))
    return "\n\n".join(parts)

async def memory_save(agent_id: UUID, mtype: str, title: str, content: str,
                      metadata: dict | None = None, tags: list | None = None):
    if metadata is None:
        metadata = {}
    if tags is None:
        tags = []
    async with DB() as db:
        await db.execute("""
            INSERT INTO agent_memory (agent_id,mtype,title,content,metadata,tags)
            VALUES ($1,$2,$3,$4,$5,$6)
            ON CONFLICT DO NOTHING
        """, agent_id, mtype, title, content, json.dumps(metadata), tags)

# ─── Fallback IA (Gemini) ─────────────────────────────────────────────────────

async def ai_fallback(skill: str, problem: str, memory_ctx: str, check_output: str) -> str:
    """
    Fallback de IA — delega ao módulo llm.py que suporta múltiplos provedores.
    Provedor, modelo e habilitação são configurados via variáveis de ambiente.
    """
    return await llm_ask(skill=skill, problem=problem,
                         memory_ctx=memory_ctx, check_output=check_output)

# ─── SSH Test Executor ────────────────────────────────────────────────────────

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
        self.rules        = agent.get("rules") or {}
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

                except Exception as e:
                    failed += 1
                    total  += 1
                    await self._emit(db, "error", f"Falha SSH: {e}", srv["name"])
                    srv_report["error"] = str(e)

                report["servers"].append(srv_report)

        return {"total": total, "passed": passed, "failed": failed, "report": report}

# ─── Web Monitor Executor ─────────────────────────────────────────────────────

class WebMonitorExecutor:
    """
    Monitora URLs HTTP/HTTPS.
    Rules:
    {
      "checks": [
        {"url": "https://app.example.com", "expect_status": 200,
         "expect_contains": "AsteriskIA", "timeout": 10},
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
        self.rules     = agent.get("rules") or {}
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

# ─── Log Monitor Executor ─────────────────────────────────────────────────────

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
        self.rules     = agent.get("rules") or {}
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

                except Exception as e:
                    failed += 1
                    total  += 1
                    await self._emit(db, "error", f"Falha SSH: {e}", srv["name"])
                    srv_report["error"] = str(e)

                report["servers"].append(srv_report)

        return {"total": total, "passed": passed, "failed": failed, "report": report}

# ─── Database Executor ────────────────────────────────────────────────────────

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
                    await self._emit(db, "success" if ok else "error", msg,
                                     dsn.split("@")[-1].split("/")[0] if "@" in dsn else dsn[:30])
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
                    await self._emit(db, "error", f"✗ {name}: {e}")
                    failed += 1; report["checks"].append({"name": name, "ok": False, "reason": str(e)})
        return {"total": total, "passed": passed, "failed": failed, "report": report}


# ─── Dispatcher ───────────────────────────────────────────────────────────────

EXECUTORS = {
    "ssh_test":    SSHTestExecutor,
    "web_monitor": WebMonitorExecutor,
    "log_monitor": LogMonitorExecutor,
    "database":    DatabaseExecutor,
}

# Lock global — previne execuções paralelas do mesmo agente
_running_agents: set[str] = set()


async def _send_all_alerts(agent: dict, level: str, message: str,
                            execution_id: UUID, db, broadcast):
    """Envia alerta por todos os canais configurados no agente."""
    from notifier import send_telegram, send_email, send_webhook
    agent_id = UUID(str(agent["id"]))

    async def _save(channel, delivered):
        await db.execute("""
            INSERT INTO alerts (agent_id, execution_id, channel, level, message, delivered)
            VALUES ($1,$2,$3,$4,$5,$6)
        """, agent_id, execution_id, channel, level, message, delivered)

    if agent.get("notify_telegram") and agent.get("telegram_chat"):
        ok = await send_telegram(agent["telegram_chat"], f"🔔 <b>{agent['name']}</b>\n{message}")
        await _save("telegram", ok)

    if agent.get("notify_email") and agent.get("notify_email_to"):
        ok = await send_email(to=agent["notify_email_to"],
            subject=f"[AsteriskIA] {agent['name']} — {level.upper()}",
            body=f"<p><b>{agent['name']}</b></p><p>{message}</p>")
        await _save("email", ok)

    if agent.get("notify_webhook") and agent.get("notify_webhook_url"):
        ok = await send_webhook(agent["notify_webhook_url"], {
            "agent": agent["name"], "level": level, "message": message,
            "execution_id": str(execution_id),
            "ts": datetime.now(timezone.utc).isoformat()
        })
        await _save("webhook", ok)

    await broadcast("__alerts__", {
        "ts": datetime.now(timezone.utc).isoformat(),
        "agent": agent["name"], "level": level, "message": message
    })
    await _save("web", True)


async def _apply_retention():
    """Remove registros mais antigos que o configurado em retention_config."""
    try:
        async with DB() as db:
            cfg = await db.fetchrow("SELECT * FROM retention_config WHERE id=1")
            if not cfg:
                return
            logs_days  = int(cfg["logs_days"])
            exec_days  = int(cfg["executions_days"])
            alert_days = int(cfg["alerts_days"])
            dl = await db.fetchval(
                "DELETE FROM execution_logs WHERE ts < NOW() - ($1 * INTERVAL '1 day') RETURNING COUNT(*)",
                logs_days)
            de = await db.fetchval(
                "DELETE FROM executions WHERE started_at < NOW() - ($1 * INTERVAL '1 day') AND status != 'running' RETURNING COUNT(*)",
                exec_days)
            da = await db.fetchval(
                "DELETE FROM alerts WHERE sent_at < NOW() - ($1 * INTERVAL '1 day') RETURNING COUNT(*)",
                alert_days)
            if any([dl, de, da]):
                logger.info("[retention] %s execuções, %s logs, %s alertas removidos", de, dl, da)
    except Exception as e:
        logger.error("[retention] Erro: %s", e)


def _calc_next_run(agent: dict):
    """Calcula próxima execução baseado no schedule."""
    from datetime import timedelta
    schedule = agent.get("schedule") or {}
    if isinstance(schedule, str):
        try:
            schedule = json.loads(schedule)
        except (json.JSONDecodeError, TypeError):
            return None
    stype  = schedule.get("type", "interval")
    value  = schedule.get("value", "5m")
    active = schedule.get("active", True)
    if not active: return None
    now = datetime.now(timezone.utc)
    try:
        if stype == "interval":
            units = {"s": 1, "m": 60, "h": 3600, "d": 86400}
            secs  = int(value[:-1]) * units.get(value[-1], 60)
            return now + timedelta(seconds=secs)
        elif stype == "cron" and HAS_CRONITER:
            from croniter import croniter  # noqa: F811
            return croniter(value, now).get_next(datetime)
        elif stype == "always":
            return now + timedelta(seconds=10)
    except (ValueError, KeyError) as e:
        logger.warning("_calc_next_run: parse falhou: %s", e)
    return None


async def run_agent(agent: dict, broadcast, _chain_depth: int = 0) -> dict:
    """Ponto de entrada principal — cria execução, roda o executor, salva resultados."""
    agent_id_str = str(agent["id"])
    agent_id     = UUID(agent_id_str)
    execution_id = uuid4()
    session_id   = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    started      = datetime.now(timezone.utc)

    # ── Lock anti-execução dupla ──────────────────────────────────────────────
    if agent_id_str in _running_agents:
        return {"execution_id": None, "status": "skipped",
                "summary": "Execução anterior ainda em andamento", "report": {}}
    _running_agents.add(agent_id_str)

    try:
        async with DB() as db:
            server_ids = [UUID(s) for s in (agent.get("server_ids") or [])]
            servers    = []
            if server_ids:
                rows    = await db.fetch("SELECT * FROM servers WHERE id=ANY($1)", server_ids)
                servers = [dict(r) for r in rows]

            secret_rows = await db.fetch(
                "SELECT key, value FROM agent_secrets WHERE agent_id=$1", agent_id)
            agent["_secrets"] = {r["key"]: r["value"] for r in secret_rows}

            await db.execute("""
                INSERT INTO executions (id,agent_id,session_id,status)
                VALUES ($1,$2,$3,'running')
            """, execution_id, agent_id, session_id)
            await db.execute(
                "UPDATE agents SET status='running', last_run=NOW() WHERE id=$1", agent_id)

        ExecutorClass = EXECUTORS.get(agent["type"])
        result = {"total": 0, "passed": 0, "failed": 0, "report": {}}
        try:
            if ExecutorClass:
                executor = ExecutorClass(agent, execution_id, broadcast)
                result   = await executor.run(servers)
            else:
                result["report"] = {"error": f"Tipo '{agent['type']}' não suportado"}
        except Exception as e:
            result["report"]["exception"] = str(e)

        finished = datetime.now(timezone.utc)
        duration = (finished - started).total_seconds()

        no_targets = (len(servers) == 0 and not agent.get("target_urls")
                      and not (agent.get("rules") or {}).get("checks"))
        if no_targets:
            status = "error"
        elif result["total"] == 0 and result["failed"] == 0:
            status = "error"
        elif result["failed"] == 0:
            status = "success"
        elif result["passed"] > 0:
            status = "partial"
        else:
            status = "error"

        summary = ("Nenhum alvo configurado para este agente" if no_targets else
                   f"{result['passed']}/{result['total']} verificações OK"
                   + (f", {result['failed']} falha(s)" if result["failed"] else "")
                   + f" em {duration:.1f}s")

        next_run = _calc_next_run(agent)

        async with DB() as db:
            await db.execute("""
                UPDATE executions SET status=$2, finished_at=$3, duration_s=$4,
                    total_checks=$5, passed_checks=$6, failed_checks=$7,
                    summary=$8, report_json=$9
                WHERE id=$1
            """, execution_id, status, finished, duration,
                 result["total"], result["passed"], result["failed"],
                 summary, json.dumps(result["report"]))

            await db.execute(
                "UPDATE agents SET status=$2, last_run=NOW(), next_run=$3 WHERE id=$1",
                agent_id, "idle", next_run)

            # ── Alertas (todos os canais) ────────────────────────────────────
            if status in ("error", "partial"):
                await _send_all_alerts(agent, status, summary, execution_id, db, broadcast)

            # ── Auto-fix via SSH ─────────────────────────────────────────────
            if status in ("error", "partial") and servers:
                rules = agent.get("rules") or {}
                if isinstance(rules, str): rules = json.loads(rules)
                for check in rules.get("checks", []):
                    fix_cmd = check.get("fix_cmd", "")
                    if fix_cmd and check.get("auto_fix", False):
                        srv = servers[0]
                        try:
                            async with await asyncssh.connect(**_build_ssh_kwargs(srv)) as conn:
                                r = await asyncio.wait_for(conn.run(fix_cmd), timeout=30)
                                await db.execute("""
                                    INSERT INTO execution_logs
                                      (execution_id,agent_id,level,server,message,raw_output)
                                    VALUES ($1,$2,'warning',$3,$4,$5)
                                """, execution_id, agent_id, srv["name"],
                                     f"🔧 Auto-fix: {fix_cmd}", r.stdout[:500])
                        except Exception as e:
                            await db.execute("""
                                INSERT INTO execution_logs
                                  (execution_id,agent_id,level,server,message)
                                VALUES ($1,$2,'error',$3,$4)
                            """, execution_id, agent_id, srv.get("name",""),
                                 f"🔧 Auto-fix falhou: {e}")

        if result["failed"] > 0:
            await memory_save(agent_id, "observation",
                f"Falhas em {session_id}", summary, tags=["failure", agent["type"]])

        # ── Encadeamento de agentes (máx. 3 níveis) ───────────────────────────
        trigger_id = agent.get("on_failure_trigger_agent_id")
        if trigger_id and status in ("error", "partial") and _chain_depth < 3:
            try:
                async with DB() as db:
                    trow = await db.fetchrow(
                        "SELECT * FROM agents WHERE id=$1", UUID(str(trigger_id)))
                if trow:
                    logger.info("[chain] %s → %s", agent["name"], trow["name"])
                    asyncio.create_task(run_agent(dict(trow), broadcast, _chain_depth + 1))
            except Exception as e:
                logger.error("[chain] Erro: %s", e)

        await broadcast(str(agent_id), {
            "ts": finished.isoformat(), "level": "info",
            "message": f"Execução finalizada: {summary}"
        })

        # ── Retenção (probabilística — 1% das execuções) ─────────────────────
        if hash(str(execution_id)) % 100 == 0:
            asyncio.create_task(_apply_retention())

        return {"execution_id": str(execution_id), "status": status,
                "summary": summary, "report": result["report"]}

    finally:
        _running_agents.discard(agent_id_str)
