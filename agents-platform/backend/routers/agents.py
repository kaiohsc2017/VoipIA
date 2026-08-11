"""routers/agents.py"""
from fastapi import APIRouter, HTTPException, Request, Depends, Query
from uuid import UUID
from models import AgentCreate, AgentOut
from database import DB
from auth import require_permission
import json, uuid, logging
from datetime import datetime, timezone

logger = logging.getLogger("asteriskia.agents")

router = APIRouter()

# Leitura (listar/detalhar/memória/stats) fica aberta a qualquer usuário autenticado.
# Escrita e execução exigem PERM_WRITE_agents.agents (ou ADMIN legado) — um
# agente roda comandos via SSH em servidores cadastrados, então qualquer
# usuário autenticado poder criar/editar/disparar um agente equivale a RCE
# remoto sem controle nenhum.
_WRITE = [Depends(require_permission("agents.agents", "write"))]

# Chaves cujo valor é sensível e nunca deve ser devolvido ao cliente em texto puro.
_SECRET_KEYS = {
    "dsn", "password", "passwd", "pwd", "secret", "token",
    "api_key", "apikey", "key", "ssh_key", "credential",
    "conn_str", "connection_string",
}


def _mask_secret_value(value):
    """Mascara um valor sensível. Para DSNs (user:pass@host) mascara só a senha."""
    if not isinstance(value, str) or not value:
        return value
    # postgresql://user:senha@host/db  →  postgresql://user:••••@host/db
    if "://" in value and "@" in value:
        scheme, _, rest = value.partition("://")
        creds, at, hostpart = rest.partition("@")
        if ":" in creds:
            user, _, _pw = creds.partition(":")
            return f"{scheme}://{user}:••••••••@{hostpart}"
        return value
    return "••••••••"


def _mask_secrets(obj):
    """Percorre recursivamente dicts/lists mascarando valores de chaves sensíveis."""
    if isinstance(obj, dict):
        return {
            k: (_mask_secret_value(v) if k.lower() in _SECRET_KEYS else _mask_secrets(v))
            for k, v in obj.items()
        }
    if isinstance(obj, list):
        return [_mask_secrets(item) for item in obj]
    return obj


def _rules_has_ssh_exec(rules: dict) -> bool:
    """True se `rules.checks[]` tiver algum `cmd`/`fix_cmd` — executado
    literalmente via SSH em qualquer servidor cadastrado (SSHTestExecutor,
    executor.py). PERM_WRITE_agents.agents (permissão granular de "editar
    agente") não deveria bastar pra isso — equivale a RCE remoto usando
    credenciais SSH que o usuário nunca viu."""
    for check in (rules or {}).get("checks", []):
        if isinstance(check, dict) and (check.get("cmd") or check.get("fix_cmd")):
            return True
    return False


def _rules_has_db_exec(rules: dict) -> bool:
    """True se `rules.checks[]` tiver algum `dsn`/`query` — executado
    literalmente contra um banco remoto (DatabaseExecutor, executor.py).
    Mesmo risco do SSH: qualquer usuário com PERM_WRITE_agents.agents
    poderia rodar SQL arbitrário contra qualquer DSN alcançável na rede."""
    for check in (rules or {}).get("checks", []):
        if isinstance(check, dict) and (check.get("dsn") or check.get("query")):
            return True
    return False


def _require_admin_for_privileged_exec(rules: dict, request: Request) -> None:
    is_admin = getattr(request.state, "role", "USER") == "ADMIN"
    if is_admin:
        return
    if _rules_has_ssh_exec(rules):
        raise HTTPException(403,
            "Definir/editar comandos SSH (checks[].cmd/fix_cmd) exige administrador")
    if _rules_has_db_exec(rules):
        raise HTTPException(403,
            "Definir/editar DSN/query de banco (checks[].dsn/query) exige administrador")


def _sanitize_agent(agent: dict) -> dict:
    """Mascara credenciais embutidas em `rules` antes de devolver o agente ao cliente.

    Preserva o tipo original de `rules` (str JSON ou dict) para não quebrar o frontend.
    """
    rules = agent.get("rules")
    if rules is None:
        return agent
    was_str = isinstance(rules, str)
    if was_str:
        try:
            rules = json.loads(rules)
        except (ValueError, TypeError):
            return agent
    masked = _mask_secrets(rules)
    out = dict(agent)
    out["rules"] = json.dumps(masked) if was_str else masked
    return out

@router.get("/")
async def list_agents(limit: int = Query(default=100, le=500), offset: int = 0):
    async with DB() as db:
        rows  = await db.fetch(
            "SELECT * FROM agents ORDER BY created_at DESC LIMIT $1 OFFSET $2", limit, offset)
        total = await db.fetchval("SELECT COUNT(*) FROM agents")
        return {"items": [_sanitize_agent(dict(r)) for r in rows], "total": total, "limit": limit, "offset": offset}

@router.post("/", response_model=dict, dependencies=_WRITE)
async def create_agent(body: AgentCreate, request: Request):
    _require_admin_for_privileged_exec(body.rules, request)
    async with DB() as db:
        row = await db.fetchrow("""
            INSERT INTO agents (name, description, type, skill, server_ids, target_urls,
                                rules, schedule, notify_telegram, telegram_chat,
                                notify_email, notify_email_to,
                                notify_webhook, notify_webhook_url,
                                on_failure_trigger_agent_id)
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15) RETURNING *
        """, body.name, body.description, body.type, body.skill,
             [str(s) for s in body.server_ids], body.target_urls,
             json.dumps(body.rules), json.dumps(body.schedule.model_dump()),
             body.notify_telegram, body.telegram_chat,
             body.notify_email, body.notify_email_to,
             body.notify_webhook, body.notify_webhook_url,
             uuid.UUID(body.on_failure_trigger_agent_id) if body.on_failure_trigger_agent_id else None)
        return _sanitize_agent(dict(row))

@router.get("/{agent_id}")
async def get_agent(agent_id: UUID):
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM agents WHERE id=$1", agent_id)
        if not row: raise HTTPException(404, "Agente não encontrado")
        return _sanitize_agent(dict(row))

@router.put("/{agent_id}", dependencies=_WRITE)
async def update_agent(agent_id: UUID, body: AgentCreate, request: Request):
    _require_admin_for_privileged_exec(body.rules, request)
    async with DB() as db:
        row = await db.fetchrow("""
            UPDATE agents SET name=$1, description=$2, type=$3, skill=$4,
                server_ids=$5, target_urls=$6, rules=$7, schedule=$8,
                notify_telegram=$9, telegram_chat=$10,
                notify_email=$11, notify_email_to=$12,
                notify_webhook=$13, notify_webhook_url=$14,
                on_failure_trigger_agent_id=$15,
                updated_at=NOW()
            WHERE id=$16 RETURNING *
        """, body.name, body.description, body.type, body.skill,
             [str(s) for s in body.server_ids], body.target_urls,
             json.dumps(body.rules), json.dumps(body.schedule.model_dump()),
             body.notify_telegram, body.telegram_chat,
             body.notify_email, body.notify_email_to,
             body.notify_webhook, body.notify_webhook_url,
             uuid.UUID(body.on_failure_trigger_agent_id) if body.on_failure_trigger_agent_id else None,
             agent_id)
        if not row: raise HTTPException(404)
        agent = dict(row)
    try:
        scheduler = request.app.state.scheduler
        scheduler.reload_agent(agent)   # usa as credenciais reais, antes de mascarar
    except Exception as e:
        logger.warning("[agents] reload_agent error: %s", e)
    return _sanitize_agent(agent)

@router.delete("/{agent_id}", dependencies=_WRITE)
async def delete_agent(agent_id: UUID):
    async with DB() as db:
        await db.execute("DELETE FROM agents WHERE id=$1", agent_id)
        return {"ok": True}

@router.post("/{agent_id}/run", dependencies=_WRITE)
async def run_agent_now(agent_id: UUID, request: Request):
    scheduler = request.app.state.scheduler
    result = await scheduler.run_now(str(agent_id))
    return result

@router.post("/{agent_id}/pause", dependencies=_WRITE)
async def pause_agent(agent_id: UUID):
    async with DB() as db:
        await db.execute("UPDATE agents SET status='paused' WHERE id=$1", agent_id)
        return {"ok": True}

@router.post("/{agent_id}/resume", dependencies=_WRITE)
async def resume_agent(agent_id: UUID):
    async with DB() as db:
        await db.execute("UPDATE agents SET status='idle' WHERE id=$1", agent_id)
        return {"ok": True}

@router.get("/{agent_id}/memory")
async def get_agent_memory(agent_id: UUID, q: str = ""):
    async with DB() as db:
        if q:
            rows = await db.fetch("""
                SELECT * FROM agent_memory
                WHERE agent_id=$1 AND (content % $2 OR title % $2)
                ORDER BY similarity(content, $2) DESC LIMIT 20
            """, agent_id, q)
        else:
            rows = await db.fetch("""
                SELECT * FROM agent_memory WHERE agent_id=$1
                ORDER BY updated_at DESC LIMIT 50
            """, agent_id)
        return [dict(r) for r in rows]

@router.get("/{agent_id}/stats")
async def agent_stats(agent_id: UUID):
    async with DB() as db:
        row = await db.fetchrow("""
            SELECT
                COUNT(*) FILTER (WHERE status='success')  AS ok,
                COUNT(*) FILTER (WHERE status='error')    AS errors,
                COUNT(*) FILTER (WHERE status='partial')  AS partial,
                COUNT(*)                                   AS total,
                AVG(duration_s)                            AS avg_duration
            FROM executions WHERE agent_id=$1
        """, agent_id)
        return dict(row)
