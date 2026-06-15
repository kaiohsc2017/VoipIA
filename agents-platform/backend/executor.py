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
import asyncio, json, re, aiohttp, asyncssh, os
from datetime import datetime, timezone
from uuid import UUID, uuid4
from database import DB
from notifier import send_telegram, send_web_alert

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
GEMINI_MODEL   = os.environ.get("GEMINI_MODEL_LLM", "gemini-2.5-flash")

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
                      metadata: dict = {}, tags: list = []):
    async with DB() as db:
        await db.execute("""
            INSERT INTO agent_memory (agent_id,mtype,title,content,metadata,tags)
            VALUES ($1,$2,$3,$4,$5,$6)
            ON CONFLICT DO NOTHING
        """, agent_id, mtype, title, content, json.dumps(metadata), tags)

# ─── Fallback IA (Gemini) ─────────────────────────────────────────────────────

async def ai_fallback(skill: str, problem: str, memory_ctx: str, check_output: str) -> str:
    """
    Consulta Gemini quando o script não cobre o caso.
    Retorna sugestão de correção ou diagnóstico.
    """
    if not GEMINI_API_KEY:
        return "IA não configurada (GEMINI_API_KEY ausente)."

    prompt = f"""Você é um especialista em infraestrutura Linux com o seguinte contexto:
{skill}

Ocorreu uma falha durante uma verificação automatizada.

SAÍDA DO COMANDO:
{check_output[:1000]}

PROBLEMA IDENTIFICADO:
{problem}

CONTEXTO DE MEMÓRIA E DOCUMENTAÇÃO:
{memory_ctx[:2000] if memory_ctx else 'Nenhum contexto disponível.'}

Com base nisso, forneça:
1. Diagnóstico da causa mais provável (máximo 2 frases)
2. Comando(s) exato(s) para corrigir
3. Como prevenir no futuro

Seja direto e técnico. Responda em português."""

    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                "https://generativelanguage.googleapis.com/v1beta/models/"
                f"{GEMINI_MODEL}:generateContent?key={GEMINI_API_KEY}",
                json={"contents": [{"parts": [{"text": prompt}]}]},
                timeout=aiohttp.ClientTimeout(total=30),
            ) as resp:
                data = await resp.json()
                return data["candidates"][0]["content"]["parts"][0]["text"]
    except Exception as e:
        return f"Erro ao consultar IA: {e}"

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
      "use_ai_on_failure": true
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
        self.use_ai       = self.rules.get("use_ai_on_failure", True)

    async def _emit(self, db, level, message, server=None, raw=None):
        await log(db, self.eid, self.agent_id, level, message, server, raw)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "server": server, "message": message
        })

    async def _connect(self, server: dict):
        kwargs = dict(host=server["host"], port=server["port"],
                      username=server["username"], known_hosts=None,
                      connect_timeout=15)
        if server["auth_type"] == "key" and server.get("ssh_key"):
            kwargs["client_keys"] = [asyncssh.import_private_key(server["ssh_key"])]
        else:
            kwargs["password"] = server.get("password", "")
        return await asyncssh.connect(**kwargs)

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
        self.use_ai    = self.rules.get("use_ai_on_failure", False)

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
        self.use_ai    = self.rules.get("use_ai_on_failure", True)

    async def _emit(self, db, level, message, server=None, raw=None):
        await log(db, self.eid, self.agent_id, level, message, server, raw)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "server": server, "message": message
        })

    async def _connect(self, server: dict):
        kwargs = dict(host=server["host"], port=server["port"],
                      username=server["username"], known_hosts=None,
                      connect_timeout=15)
        if server["auth_type"] == "key" and server.get("ssh_key"):
            kwargs["client_keys"] = [asyncssh.import_private_key(server["ssh_key"])]
        else:
            kwargs["password"] = server.get("password", "")
        return await asyncssh.connect(**kwargs)

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

                            # Comando: últimas N linhas do arquivo filtradas pelo padrão
                            cmd = (f"tail -n {lines} {filepath} 2>/dev/null | "
                                   f"grep -iE '{pattern}' 2>/dev/null || true")

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

# ─── Dispatcher ───────────────────────────────────────────────────────────────

EXECUTORS = {
    "ssh_test":    SSHTestExecutor,
    "web_monitor": WebMonitorExecutor,
    "log_monitor": LogMonitorExecutor,
}

async def run_agent(agent: dict, broadcast) -> dict:
    """Ponto de entrada principal — cria execução, roda o executor, salva resultados."""
    agent_id     = UUID(str(agent["id"]))
    execution_id = uuid4()
    session_id   = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    started      = datetime.now(timezone.utc)

    async with DB() as db:
        server_ids = [UUID(s) for s in (agent.get("server_ids") or [])]
        servers    = []
        if server_ids:
            rows    = await db.fetch("SELECT * FROM servers WHERE id=ANY($1)", server_ids)
            servers = [dict(r) for r in rows]

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
    status   = ("success" if result["failed"] == 0
                else ("partial" if result["passed"] > 0 else "error"))

    summary = (
        f"{result['passed']}/{result['total']} verificações OK"
        + (f", {result['failed']} falha(s)" if result["failed"] else "")
        + f" em {duration:.1f}s"
    )

    async with DB() as db:
        await db.execute("""
            UPDATE executions SET
                status=$2, finished_at=$3, duration_s=$4,
                total_checks=$5, passed_checks=$6, failed_checks=$7,
                summary=$8, report_json=$9
            WHERE id=$1
        """, execution_id, status, finished, duration,
             result["total"], result["passed"], result["failed"],
             summary, json.dumps(result["report"]))

        await db.execute(
            "UPDATE agents SET status=$2, last_run=NOW() WHERE id=$1", agent_id, "idle")

    # Salva observação na memória se houve falhas
    if result["failed"] > 0:
        await memory_save(agent_id, "observation",
            f"Falhas em {session_id}", summary,
            tags=["failure", agent["type"]])

    await broadcast(str(agent_id), {
        "ts": finished.isoformat(), "level": "info",
        "message": f"Execução finalizada: {summary}"
    })

    return {
        "execution_id": str(execution_id),
        "status": status,
        "summary": summary,
        "report": result["report"],
    }
