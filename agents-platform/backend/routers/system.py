"""routers/system.py — health check, retenção de dados, secrets por agente"""
from fastapi import APIRouter, HTTPException
from uuid import UUID
from database import DB
import asyncio

router = APIRouter()

# ── Health Check ──────────────────────────────────────────────────────────────

@router.get("/health")
async def health():
    """Health check público — usado por uptime monitors externos."""
    db_ok = False
    try:
        async with DB() as db:
            await db.fetchval("SELECT 1")
            db_ok = True
    except Exception:
        pass

    agents_count = 0
    running_count = 0
    try:
        async with DB() as db:
            agents_count  = await db.fetchval("SELECT COUNT(*) FROM agents")
            running_count = await db.fetchval("SELECT COUNT(*) FROM agents WHERE status='running'")
    except Exception:
        pass

    status = "ok" if db_ok else "degraded"
    return {
        "status":   status,
        "database": "ok" if db_ok else "error",
        "agents":   agents_count,
        "running":  running_count,
        "version":  "2.0.0"
    }

# ── Retenção de dados ─────────────────────────────────────────────────────────

@router.get("/retention")
async def get_retention():
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM retention_config WHERE id=1")
        return dict(row) if row else {}

@router.put("/retention")
async def update_retention(body: dict):
    exec_days  = int(body.get("executions_days", 90))
    logs_days  = int(body.get("logs_days", 30))
    alert_days = int(body.get("alerts_days", 180))

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

@router.post("/retention/run")
async def run_retention_now():
    """Força execução imediata da limpeza de dados."""
    from executor import _apply_retention
    asyncio.create_task(_apply_retention())
    return {"ok": True, "message": "Limpeza iniciada em background"}

# ── Secrets por agente ────────────────────────────────────────────────────────

@router.get("/agents/{agent_id}/secrets")
async def list_secrets(agent_id: UUID):
    async with DB() as db:
        rows = await db.fetch(
            "SELECT id, key, created_at FROM agent_secrets WHERE agent_id=$1 ORDER BY key",
            agent_id)
        return [dict(r) for r in rows]  # value nunca retornado na listagem

@router.post("/agents/{agent_id}/secrets")
async def upsert_secret(agent_id: UUID, body: dict):
    key   = str(body.get("key", "")).strip()
    value = str(body.get("value", "")).strip()
    if not key or not value:
        raise HTTPException(400, "key e value são obrigatórios")
    async with DB() as db:
        await db.execute("""
            INSERT INTO agent_secrets (agent_id, key, value)
            VALUES ($1, $2, $3)
            ON CONFLICT (agent_id, key) DO UPDATE SET value=$3
        """, agent_id, key, value)
    return {"ok": True, "key": key}

@router.delete("/agents/{agent_id}/secrets/{key}")
async def delete_secret(agent_id: UUID, key: str):
    async with DB() as db:
        await db.execute(
            "DELETE FROM agent_secrets WHERE agent_id=$1 AND key=$2", agent_id, key)
    return {"ok": True}
