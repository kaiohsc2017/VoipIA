"""
orchestrator.py — motor de execução dos agentes (fase 23, O3.4 da refatoração,
extraído de executor.py): cria a execução, despacha para o executor certo
(executors/), agrega o resultado, dispara alertas/auto-fix/encadeamento e aplica
retenção.

1. Cada executor tenta resolver com as regras do agente (sem IA)
2. Se não coberto, consultam memória coletiva dos agentes
3. Se ainda sem resposta, fazem fallback para Gemini com contexto completo
"""
import asyncio
import json
import logging
from datetime import datetime, timezone
from uuid import UUID, uuid4

import asyncssh

from database import DB
from executors import EXECUTORS, _build_ssh_kwargs
from executors.common import memory_save

logger = logging.getLogger("asteriskia.executor")

try:
    from croniter import croniter as _croniter  # noqa: F401
    HAS_CRONITER = True
except ImportError:
    HAS_CRONITER = False

# Lock global — previne execuções paralelas do mesmo agente
_running_agents: set[str] = set()

# Referências das tasks de background (encadeamento de agentes, retenção) —
# evita que o GC colete a task antes de terminar e exceções sumam em silêncio.
_background_tasks: set[asyncio.Task] = set()


def _spawn_background_task(coro) -> asyncio.Task:
    """Cria uma task de background mantendo referência forte até ela terminar."""
    task = asyncio.create_task(coro)
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)
    return task


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
            subject=f"[VoipIA] {agent['name']} — {level.upper()}",
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
            # asyncpg .execute() devolve o status ("DELETE N"); COUNT(*) em
            # RETURNING é SQL inválido, então parseamos o contador do status.
            def _deleted(status: str) -> int:
                try:
                    return int(status.rsplit(" ", 1)[-1])
                except (ValueError, AttributeError):
                    return 0
            dl = _deleted(await db.execute(
                "DELETE FROM execution_logs WHERE ts < NOW() - ($1 * INTERVAL '1 day')",
                logs_days))
            de = _deleted(await db.execute(
                "DELETE FROM executions WHERE started_at < NOW() - ($1 * INTERVAL '1 day') AND status != 'running'",
                exec_days))
            da = _deleted(await db.execute(
                "DELETE FROM alerts WHERE sent_at < NOW() - ($1 * INTERVAL '1 day')",
                alert_days))
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

        # rules vem como string JSON crua do banco (asyncpg não decodifica jsonb
        # automaticamente) — scheduler.py nunca normaliza antes de chamar
        # run_agent(). Achado emergente durante o teste de O1.4: sem isso,
        # AttributeError em qualquer agente sem server_ids/target_urls (ex:
        # agentes type=database, que embutem o DSN em cada check).
        _rules_for_targets = agent.get("rules") or {}
        if isinstance(_rules_for_targets, str):
            _rules_for_targets = json.loads(_rules_for_targets)
        no_targets = (len(servers) == 0 and not agent.get("target_urls")
                      and not _rules_for_targets.get("checks"))
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
            # Roda fix_cmd só no(s) servidor(es) onde ESSE check especificamente
            # falhou (via result["report"]["servers"]), não sempre em servers[0] —
            # com múltiplos servidores, o comando de correção do host errado não
            # deveria ser disparado (achado da auditoria, executor.py O1.5).
            if status in ("error", "partial") and servers:
                rules = agent.get("rules") or {}
                if isinstance(rules, str): rules = json.loads(rules)
                report_servers  = result["report"].get("servers", [])
                servers_by_name = {s["name"]: s for s in servers}
                for check in rules.get("checks", []):
                    fix_cmd = check.get("fix_cmd", "")
                    if not (fix_cmd and check.get("auto_fix", False)):
                        continue
                    check_name = check.get("name", check.get("cmd", "check"))
                    failed_on = [
                        srv_rep["server"] for srv_rep in report_servers
                        if any(c.get("name") == check_name and not c.get("ok")
                               for c in srv_rep.get("checks", []))
                    ]
                    targets = [servers_by_name[n] for n in failed_on if n in servers_by_name]
                    if not targets:
                        # Sem match no relatório (formato inesperado) — mantém o
                        # comportamento anterior como fallback, não trava o auto-fix.
                        targets = [servers[0]]
                    for srv in targets:
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
                    _spawn_background_task(run_agent(dict(trow), broadcast, _chain_depth + 1))
            except Exception as e:
                logger.error("[chain] Erro: %s", e)

        await broadcast(str(agent_id), {
            "ts": finished.isoformat(), "level": "info",
            "message": f"Execução finalizada: {summary}"
        })

        # ── Retenção (probabilística — 1% das execuções) ─────────────────────
        # execution_id.int é determinístico (não depende de PYTHONHASHSEED, que
        # hash(str(...)) usa e varia por processo/restart) e uniformemente
        # distribuído, já que execution_id é um uuid4() aleatório.
        if execution_id.int % 100 == 0:
            _spawn_background_task(_apply_retention())

        return {"execution_id": str(execution_id), "status": status,
                "summary": summary, "report": result["report"]}

    finally:
        _running_agents.discard(agent_id_str)
