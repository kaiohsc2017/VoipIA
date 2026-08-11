"""routers/executions.py"""
from fastapi import APIRouter, Depends, Query
from uuid import UUID
from database import DB, fetch_recent_alerts
from auth import require_admin

router = APIRouter()

# execution_logs.message pode conter DSN/senha vazada em mensagens de exceção
# de asyncpg/asyncssh — esse endpoint não passa pelo _mask_secrets de agents.py.
_ADMIN = [Depends(require_admin)]

@router.get("/")
async def list_executions(agent_id: str = None,
                           limit: int = Query(default=50, le=500), offset: int = 0):
    async with DB() as db:
        if agent_id:
            rows = await db.fetch("""
                SELECT e.*, a.name as agent_name FROM executions e
                JOIN agents a ON a.id=e.agent_id
                WHERE e.agent_id=$1 ORDER BY e.started_at DESC LIMIT $2 OFFSET $3
            """, UUID(agent_id), limit, offset)
            total = await db.fetchval(
                "SELECT COUNT(*) FROM executions WHERE agent_id=$1", UUID(agent_id))
        else:
            rows = await db.fetch("""
                SELECT e.*, a.name as agent_name FROM executions e
                JOIN agents a ON a.id=e.agent_id
                ORDER BY e.started_at DESC LIMIT $1 OFFSET $2
            """, limit, offset)
            total = await db.fetchval("SELECT COUNT(*) FROM executions")
        return {"items": [dict(r) for r in rows], "total": total, "limit": limit, "offset": offset}

@router.get("/dashboard/summary")
async def dashboard_summary():
    """Cards do topo: agentes ativos, execuções OK/erro últimas 24h, alertas 24h."""
    async with DB() as db:
        agents_row = await db.fetchrow(
            "SELECT COUNT(*) AS total FROM agents WHERE status != 'paused'"
        )
        exec_row = await db.fetchrow("""
            SELECT
                COUNT(*) FILTER (WHERE status = 'success') AS ok,
                COUNT(*) FILTER (WHERE status IN ('error','partial')) AS errors
            FROM executions
            WHERE started_at >= NOW() - INTERVAL '24 hours'
        """)
        alerts_row = await db.fetchrow("""
            SELECT COUNT(*) AS total FROM alerts
            WHERE sent_at >= NOW() - INTERVAL '24 hours'
        """)
        recent = await db.fetch("""
            SELECT e.id, a.name AS agent_name, e.status,
                   e.passed_checks, e.total_checks, e.failed_checks,
                   e.duration_s, e.started_at
            FROM executions e
            JOIN agents a ON a.id = e.agent_id
            ORDER BY e.started_at DESC LIMIT 10
        """)
        return {
            "active_agents":   agents_row["total"],
            "executions_24h":  {"ok": exec_row["ok"], "errors": exec_row["errors"]},
            "alerts_24h":      alerts_row["total"],
            "recent_executions": [dict(r) for r in recent],
        }

@router.get("/dashboard/period")
async def dashboard_period(period: str = "day"):
    """Tabela 'Por período': totais por agente em 24h / 7d / 30d."""
    if period not in {"day", "week", "month"}:
        period = "day"
    async with DB() as db:
        rows = await db.fetch("""
            SELECT
                a.name AS agent_name,
                COUNT(*)                                        AS total,
                COUNT(*) FILTER (WHERE e.status = 'success')   AS ok,
                COUNT(*) FILTER (WHERE e.status IN ('error','partial')) AS errors,
                AVG(e.duration_s)                              AS avg_duration,
                COALESCE(SUM(e.failed_checks), 0)              AS failures
            FROM executions e
            JOIN agents a ON a.id = e.agent_id
            WHERE e.started_at >= NOW() - CASE $1
                WHEN 'day'  THEN INTERVAL '24 hours'
                WHEN 'week' THEN INTERVAL '7 days'
                ELSE             INTERVAL '30 days'
            END
            GROUP BY a.id, a.name
            ORDER BY a.name
        """, period)
        return [dict(r) for r in rows]

@router.get("/alerts")
async def list_alerts(limit: int = Query(default=100, le=500)):
    """Histórico de alertas para a página Alertas/Relatórios."""
    rows = await fetch_recent_alerts(limit)
    return [
        {
            "id": r["id"],
            "agent_name": r["agent_name"],
            "level": r["level"],
            "channel": r["channel"],
            "message": r["message"],
            "sent_at": r["sent_at"],
            "delivered": r["delivered"],
        }
        for r in rows
    ]

@router.get("/{execution_id}", dependencies=_ADMIN)
async def get_execution(execution_id: UUID):
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM executions WHERE id=$1", execution_id)
        return dict(row) if row else {}

@router.get("/{execution_id}/logs", dependencies=_ADMIN)
async def get_logs(execution_id: UUID, level: str = None,
                    limit: int = Query(default=500, le=1000)):
    async with DB() as db:
        if level:
            rows = await db.fetch("""
                SELECT * FROM execution_logs WHERE execution_id=$1 AND level=$2
                ORDER BY ts ASC LIMIT $3
            """, execution_id, level, limit)
        else:
            rows = await db.fetch("""
                SELECT * FROM execution_logs WHERE execution_id=$1
                ORDER BY ts ASC LIMIT $2
            """, execution_id, limit)
        return [dict(r) for r in rows]
