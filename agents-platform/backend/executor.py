"""
executor.py — Motor de execução dos agentes
Cada agente tem um executor especializado baseado no seu tipo.
Script Python puro para os casos cobertos; fallback para IA quando necessário.
"""
import asyncio, json, re, aiohttp, asyncssh
from datetime import datetime, timezone
from uuid import UUID, uuid4
from database import DB
from notifier import send_telegram, send_web_alert

# ─── Helpers ──────────────────────────────────────────────────────────────────

async def log(db, execution_id: UUID, agent_id: UUID, level: str,
              message: str, server: str = None, raw: str = None):
    await db.execute("""
        INSERT INTO execution_logs (execution_id, agent_id, level, server, message, raw_output)
        VALUES ($1,$2,$3,$4,$5,$6)
    """, execution_id, agent_id, level, server, message, raw)

async def update_execution(db, eid: UUID, **kwargs):
    sets  = ", ".join(f"{k}=${i+2}" for i, k in enumerate(kwargs))
    vals  = list(kwargs.values())
    await db.execute(f"UPDATE executions SET {sets} WHERE id=$1", eid, *vals)

# ─── SSH Test Executor ────────────────────────────────────────────────────────

class SSHTestExecutor:
    """
    Interpreta o campo `skill` do agente como regras de teste SSH.
    Formato das rules (JSONB):
    {
      "checks": [
        {"name": "nginx rodando", "cmd": "systemctl is-active nginx", "expect": "active"},
        {"name": "porta 80 aberta", "cmd": "ss -tlnp | grep :80", "expect_contains": ":80"},
        {"name": "disco < 80%", "cmd": "df / | awk 'NR==2{print $5}'", "expect_lt": "80%"},
        {"name": "custom script", "cmd": "/opt/check.sh", "expect_exit": 0}
      ],
      "timeout_per_check": 30,
      "stop_on_first_failure": false
    }
    Quando nenhuma rule cobre o caso, usa o skill (contexto) + IA como fallback.
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

    async def _emit(self, db, level, message, server=None, raw=None):
        await log(db, self.eid, self.agent_id, level, message, server, raw)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "server": server, "message": message
        })

    async def _connect(self, server: dict) -> asyncssh.SSHClientConnection:
        kwargs = dict(host=server["host"], port=server["port"],
                      username=server["username"], known_hosts=None,
                      connect_timeout=15)
        if server["auth_type"] == "key" and server.get("ssh_key"):
            kwargs["client_keys"] = [asyncssh.import_private_key(server["ssh_key"])]
        else:
            kwargs["password"] = server.get("password","")
        return await asyncssh.connect(**kwargs)

    def _evaluate(self, check: dict, stdout: str, exit_code: int) -> tuple[bool, str]:
        out = stdout.strip()
        if "expect" in check:
            ok = out == check["expect"]
            return ok, f"esperado '{check['expect']}', obtido '{out}'"
        if "expect_contains" in check:
            ok = check["expect_contains"] in out
            return ok, f"'{check['expect_contains']}' {'encontrado' if ok else 'NÃO encontrado'} na saída"
        if "expect_exit" in check:
            ok = exit_code == check["expect_exit"]
            exp = check["expect_exit"]
            msg = 'ok' if ok else f'esperado {exp}'
            return ok, f"exit code {exit_code} ({msg})"
        if "expect_lt" in check:
            try:
                val = int(re.sub(r"[^\d]","",out))
                thr = int(re.sub(r"[^\d]","",str(check["expect_lt"])))
                ok  = val < thr
                return ok, f"{val} {'<' if ok else '>='} {thr}"
            except Exception:
                return False, f"não foi possível comparar '{out}'"
        if "expect_regex" in check:
            ok = bool(re.search(check["expect_regex"], out))
            return ok, f"regex {'casou' if ok else 'não casou'}"
        # sem regra de avaliação: sucesso se exit 0
        return exit_code == 0, f"exit {exit_code}"

    async def run(self, servers: list[dict]) -> dict:
        total = passed = failed = 0
        report = {"servers": []}

        async with DB() as db:
            await self._emit(db, "info", f"Iniciando {len(servers)} servidor(es), {len(self.checks)} verificação(ões)")

            for srv in servers:
                srv_report = {"server": srv["name"], "host": srv["host"], "checks": []}
                await self._emit(db, "info", f"Conectando a {srv['name']} ({srv['host']}:{srv['port']})", srv["name"])

                try:
                    async with await self._connect(srv) as conn:
                        await self._emit(db, "success", "SSH conectado", srv["name"])

                        for check in self.checks:
                            total += 1
                            name = check.get("name", check.get("cmd","check"))
                            try:
                                result = await asyncio.wait_for(
                                    conn.run(check["cmd"]), timeout=self.timeout
                                )
                                ok, reason = self._evaluate(check, result.stdout, result.exit_status)
                            except asyncio.TimeoutError:
                                ok, reason = False, f"timeout ({self.timeout}s)"
                                result = type("R", (), {"stdout":"","exit_status":-1})()

                            level = "success" if ok else "error"
                            if ok: passed += 1
                            else:  failed += 1

                            fix = check.get("fix_hint","") if not ok else ""
                            await self._emit(db, level,
                                f"{'✓' if ok else '✗'} {name}: {reason}"
                                + (f" → {fix}" if fix else ""),
                                srv["name"], result.stdout[:500])

                            srv_report["checks"].append({
                                "name": name, "ok": ok, "reason": reason,
                                "fix_hint": fix, "output": result.stdout[:200]
                            })

                            if not ok and self.stop_on_fail:
                                break

                except Exception as e:
                    failed += 1; total += 1
                    await self._emit(db, "error", f"Falha de conexão SSH: {e}", srv["name"])
                    srv_report["error"] = str(e)

                report["servers"].append(srv_report)

        return {"total": total, "passed": passed, "failed": failed, "report": report}


# ─── Web Monitor Executor ─────────────────────────────────────────────────────

class WebMonitorExecutor:
    """
    Monitora URLs definidas em target_urls do agente.
    Rules suportadas:
    {
      "checks": [
        {"url": "https://app.example.com", "expect_status": 200,
         "expect_contains": "AsteriskIA", "timeout": 10},
        {"url": "https://api.example.com/health", "expect_json_key": "status",
         "expect_json_value": "ok"}
      ],
      "alert_on_failure": true
    }
    """
    def __init__(self, agent, execution_id, broadcast):
        self.agent    = agent
        self.eid      = execution_id
        self.agent_id = UUID(str(agent["id"]))
        self.broadcast = broadcast
        self.rules    = agent.get("rules") or {}
        self.checks   = self.rules.get("checks", [
            {"url": u, "expect_status": 200} for u in (agent.get("target_urls") or [])
        ])

    async def _emit(self, db, level, message, server=None):
        await log(db, self.eid, self.agent_id, level, message, server)
        await self.broadcast(str(self.agent_id), {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": level, "server": server, "message": message
        })

    async def run(self, servers: list) -> dict:
        total = passed = failed = 0
        report = {"urls": []}

        async with DB() as db, aiohttp.ClientSession() as session:
            await self._emit(db, "info", f"Monitorando {len(self.checks)} URL(s)")

            for check in self.checks:
                url  = check.get("url","")
                tout = check.get("timeout", 15)
                total += 1
                entry = {"url": url, "ok": False, "reason": ""}

                try:
                    async with session.get(url, timeout=aiohttp.ClientTimeout(total=tout),
                                           ssl=False) as resp:
                        body   = await resp.text()
                        status = resp.status
                        ok     = True
                        reason = f"HTTP {status}"

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
                                if str(val) != str(check.get("expect_json_value","")):
                                    ok     = False
                                    reason = f"JSON {check['expect_json_key']}={val} ≠ {check.get('expect_json_value')}"
                            except Exception:
                                ok = False; reason = "resposta não é JSON válido"

                except aiohttp.ClientError as e:
                    ok = False; reason = f"erro de conexão: {e}"
                except asyncio.TimeoutError:
                    ok = False; reason = f"timeout ({tout}s)"

                if ok: passed += 1
                else:  failed += 1

                level = "success" if ok else "error"
                entry.update({"ok": ok, "reason": reason})
                report["urls"].append(entry)
                await self._emit(db, level, f"{'✓' if ok else '✗'} {url} → {reason}", url)

                if not ok and self.agent.get("notify_telegram") and self.agent.get("telegram_chat"):
                    await send_telegram(
                        self.agent["telegram_chat"],
                        f"🚨 [{self.agent['name']}] {url}\n{reason}"
                    )
                    async with DB() as db2:
                        await db2.execute("""
                            INSERT INTO alerts (agent_id, execution_id, channel, level, message, delivered)
                            VALUES ($1,$2,'telegram','error',$3,true)
                        """, self.agent_id, self.eid, f"{url}: {reason}")

        return {"total": total, "passed": passed, "failed": failed, "report": report}


# ─── Memory-assisted fallback ─────────────────────────────────────────────────

async def memory_recall(agent_id: UUID, query: str) -> str:
    """
    Busca memória do próprio agente + outros agentes (consulta coletiva).
    Retorna texto formatado para usar como contexto.
    """
    async with DB() as db:
        # Memória própria
        own = await db.fetch("""
            SELECT title, content, mtype FROM agent_memory
            WHERE agent_id=$1 AND (content % $2 OR title % $2)
            ORDER BY similarity(content, $2) DESC LIMIT 5
        """, agent_id, query)

        # Memória coletiva (outros agentes, ordenado por utilidade)
        shared = await db.fetch("""
            SELECT am.title, am.content, am.mtype, a.name as agent_name
            FROM agent_memory am JOIN agents a ON a.id=am.agent_id
            WHERE am.agent_id != $1 AND (am.content % $2 OR am.title % $2)
            ORDER BY am.usefulness DESC, similarity(am.content, $2) DESC LIMIT 3
        """, agent_id, query)

        # Base de conhecimento
        docs = await db.fetch("""
            SELECT filename, LEFT(content, 600) as excerpt
            FROM knowledge_docs WHERE content % $1
            ORDER BY similarity(content, $1) DESC LIMIT 2
        """, query)

    parts = []
    if own:
        parts.append("### Memória própria\n" + "\n".join(f"- [{r['mtype']}] {r['title']}: {r['content'][:200]}" for r in own))
    if shared:
        parts.append("### Memória de outros agentes\n" + "\n".join(f"- [{r['agent_name']}] {r['title']}: {r['content'][:200]}" for r in shared))
    if docs:
        parts.append("### Base de conhecimento\n" + "\n".join(f"- {r['filename']}: {r['excerpt']}" for r in docs))

    return "\n\n".join(parts)

async def memory_save(agent_id: UUID, mtype: str, title: str, content: str,
                      metadata: dict = {}, tags: list = []):
    async with DB() as db:
        await db.execute("""
            INSERT INTO agent_memory (agent_id, mtype, title, content, metadata, tags)
            VALUES ($1,$2,$3,$4,$5,$6)
            ON CONFLICT DO NOTHING
        """, agent_id, mtype, title, content, json.dumps(metadata), tags)


# ─── Dispatcher ───────────────────────────────────────────────────────────────

EXECUTORS = {
    "ssh_test":   SSHTestExecutor,
    "web_monitor": WebMonitorExecutor,
}

async def run_agent(agent: dict, broadcast) -> dict:
    """Ponto de entrada principal. Cria execução, roda o executor, salva resultados."""
    agent_id     = UUID(str(agent["id"]))
    execution_id = uuid4()
    session_id   = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    started      = datetime.now(timezone.utc)

    async with DB() as db:
        # Busca servidores
        server_ids = [UUID(s) for s in (agent.get("server_ids") or [])]
        servers    = []
        if server_ids:
            rows = await db.fetch("SELECT * FROM servers WHERE id = ANY($1)", server_ids)
            servers = [dict(r) for r in rows]

        # Cria registro de execução
        await db.execute("""
            INSERT INTO executions (id, agent_id, session_id, status)
            VALUES ($1,$2,$3,'running')
        """, execution_id, agent_id, session_id)

        # Atualiza status do agente
        await db.execute("UPDATE agents SET status='running', last_run=NOW() WHERE id=$1", agent_id)

    ExecutorClass = EXECUTORS.get(agent["type"])
    result = {"total": 0, "passed": 0, "failed": 0, "report": {}}

    try:
        if ExecutorClass:
            executor = ExecutorClass(agent, execution_id, broadcast)
            result   = await executor.run(servers)
        else:
            result["report"] = {"error": f"Tipo de agente '{agent['type']}' não suportado"}

        status = "success" if result["failed"] == 0 else ("partial" if result["passed"] > 0 else "error")

    except Exception as e:
        status = "error"
        result["report"]["exception"] = str(e)

    finished = datetime.now(timezone.utc)
    duration = (finished - started).total_seconds()

    # Gera sumário
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

        await db.execute("""
            UPDATE agents SET status=$2, next_run=NULL WHERE id=$1
        """, agent_id, "idle")

    # Salva aprendizado na memória do agente
    if result["failed"] > 0:
        await memory_save(agent_id, "observation",
            f"Falhas em {session_id}",
            summary + " | " + json.dumps(result["report"])[:500],
            tags=["failure", agent["type"]])

    await broadcast(str(agent_id), {
        "ts": finished.isoformat(), "level": "info",
        "message": f"Execução finalizada: {summary}"
    })

    return {"execution_id": str(execution_id), "status": status, "summary": summary}
