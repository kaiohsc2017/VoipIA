"""routers/reports.py"""
from fastapi import APIRouter
from uuid import UUID
from database import DB
from datetime import datetime, timedelta

router = APIRouter()

@router.get("/dashboard")
async def dashboard():
    async with DB() as db:
        agents   = await db.fetchval("SELECT COUNT(*) FROM agents WHERE status != 'paused'")
        ok       = await db.fetchval("SELECT COUNT(*) FROM executions WHERE status='success' AND started_at > NOW()-INTERVAL '24h'")
        errors   = await db.fetchval("SELECT COUNT(*) FROM executions WHERE status='error'   AND started_at > NOW()-INTERVAL '24h'")
        alerts   = await db.fetchval("SELECT COUNT(*) FROM alerts WHERE sent_at > NOW()-INTERVAL '24h'")
        recent   = await db.fetch("""
            SELECT e.id, e.status, e.started_at, e.duration_s,
                   e.total_checks, e.passed_checks, e.failed_checks,
                   a.name as agent_name
            FROM executions e JOIN agents a ON a.id=e.agent_id
            ORDER BY e.started_at DESC LIMIT 10
        """)
        return {
            "active_agents": agents,
            "executions_24h": {"ok": ok, "errors": errors},
            "alerts_24h": alerts,
            "recent_executions": [dict(r) for r in recent],
        }

@router.get("/by-period")
async def by_period(period: str = "day"):
    intervals = {"day": "24 hours", "week": "7 days", "month": "30 days"}
    interval  = intervals.get(period, "24 hours")
    async with DB() as db:
        rows = await db.fetch(f"""
            SELECT
                a.name as agent_name,
                COUNT(*) as total,
                COUNT(*) FILTER (WHERE e.status='success') as ok,
                COUNT(*) FILTER (WHERE e.status='error')   as errors,
                AVG(e.duration_s) as avg_duration,
                SUM(e.total_checks)  as checks,
                SUM(e.failed_checks) as failures
            FROM executions e JOIN agents a ON a.id=e.agent_id
            WHERE e.started_at > NOW() - INTERVAL '{interval}'
            GROUP BY a.name ORDER BY errors DESC
        """)
        return [dict(r) for r in rows]

@router.get("/alerts")
async def recent_alerts(limit: int = 50):
    async with DB() as db:
        rows = await db.fetch("""
            SELECT al.*, a.name as agent_name
            FROM alerts al JOIN agents a ON a.id=al.agent_id
            ORDER BY al.sent_at DESC LIMIT $1
        """, limit)
        return [dict(r) for r in rows]
