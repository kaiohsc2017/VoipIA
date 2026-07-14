"""routers/system.py — health check, retenção de dados, secrets por agente"""
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from uuid import UUID
from database import DB
from auth import require_admin, require_permission
import asyncio, logging


class RetentionConfigRequest(BaseModel):
    executions_days: int = 90
    logs_days: int = 30
    alerts_days: int = 180


class SecretRequest(BaseModel):
    key: str
    value: str

logger = logging.getLogger("asteriskia.system")

router = APIRouter()

# Retenção: config operacional sem menu próprio no catálogo de recursos —
# continua restrita a ADMIN puro (não há um resource_key pra checar).
# /health continua público (ver _PUBLIC em main.py, usado por uptime monitors).
_ADMIN = [Depends(require_admin)]

# Secrets por agente (credenciais) — tela "Secrets" no catálogo.
_SECRETS_READ  = [Depends(require_permission("agents.secrets", "read"))]
_SECRETS_WRITE = [Depends(require_permission("agents.secrets", "write"))]

# ── Health Check ──────────────────────────────────────────────────────────────

@router.get("/health")
async def health():
    """Health check público — usado por uptime monitors externos."""
    db_ok = False
    try:
        async with DB() as db:
            await db.fetchval("SELECT 1")
            db_ok = True
    except Exception as e:
        logger.error("[health] DB check falhou: %s", e)

    agents_count = 0
    running_count = 0
    try:
        async with DB() as db:
            agents_count  = await db.fetchval("SELECT COUNT(*) FROM agents")
            running_count = await db.fetchval("SELECT COUNT(*) FROM agents WHERE status='running'")
    except Exception as e:
        logger.error("[health] Contagem de agentes falhou: %s", e)

    status = "ok" if db_ok else "degraded"
    return {
        "status":   status,
        "database": "ok" if db_ok else "error",
        "agents":   agents_count,
        "running":  running_count,
        "version":  "2.0.0"
    }

# ── Retenção de dados ─────────────────────────────────────────────────────────

@router.get("/retention", dependencies=_ADMIN)
async def get_retention():
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM retention_config WHERE id=1")
        return dict(row) if row else {}

@router.put("/retention", dependencies=_ADMIN)
async def update_retention(body: RetentionConfigRequest):
    exec_days  = body.executions_days
    logs_days  = body.logs_days
    alert_days = body.alerts_days

    if any(d < 1 for d in [exec_days, logs_days, alert_days]):
        raise HTTPException(400, "Dias devem ser >= 1")

    async with DB() as db:
        await db.execute("""
            INSERT INTO retention_config (id, executions_days, logs_days, alerts_days, updated_at)
            VALUES (1, $1, $2, $3, NOW())
            ON CONFLICT (id) DO UPDATE SET
                executions_days=$1, logs_days=$2, alerts_days=$3, updated_at=NOW()
        """, exec_days, logs_days, alert_days)
    return {"ok": True, "executions_days": exec_days,
            "logs_days": logs_days, "alerts_days": alert_days}

@router.post("/retention/run", dependencies=_ADMIN)
async def run_retention_now():
    """Força execução imediata da limpeza de dados."""
    from executor import _apply_retention
    asyncio.create_task(_apply_retention())
    return {"ok": True, "message": "Limpeza iniciada em background"}

# ── Secrets por agente ────────────────────────────────────────────────────────

@router.get("/agents/{agent_id}/secrets", dependencies=_SECRETS_READ)
async def list_secrets(agent_id: UUID):
    async with DB() as db:
        rows = await db.fetch(
            "SELECT id, key, created_at FROM agent_secrets WHERE agent_id=$1 ORDER BY key",
            agent_id)
        return [dict(r) for r in rows]  # value nunca retornado na listagem

@router.post("/agents/{agent_id}/secrets", dependencies=_SECRETS_WRITE)
async def upsert_secret(agent_id: UUID, body: SecretRequest):
    key   = body.key.strip()
    value = body.value.strip()
    if not key or not value:
        raise HTTPException(400, "key e value são obrigatórios")
    async with DB() as db:
        await db.execute("""
            INSERT INTO agent_secrets (agent_id, key, value)
            VALUES ($1, $2, $3)
            ON CONFLICT (agent_id, key) DO UPDATE SET value=$3
        """, agent_id, key, value)
    return {"ok": True, "key": key}

@router.delete("/agents/{agent_id}/secrets/{key}", dependencies=_SECRETS_WRITE)
async def delete_secret(agent_id: UUID, key: str):
    async with DB() as db:
        await db.execute(
            "DELETE FROM agent_secrets WHERE agent_id=$1 AND key=$2", agent_id, key)
    return {"ok": True}
