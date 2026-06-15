"""routers/executions.py"""
from fastapi import APIRouter
from uuid import UUID
from database import DB

router = APIRouter()

@router.get("/")
async def list_executions(agent_id: str = None, limit: int = 50):
    async with DB() as db:
        if agent_id:
            rows = await db.fetch("""
                SELECT e.*, a.name as agent_name FROM executions e
                JOIN agents a ON a.id=e.agent_id
                WHERE e.agent_id=$1 ORDER BY e.started_at DESC LIMIT $2
            """, UUID(agent_id), limit)
        else:
            rows = await db.fetch("""
                SELECT e.*, a.name as agent_name FROM executions e
                JOIN agents a ON a.id=e.agent_id
                ORDER BY e.started_at DESC LIMIT $1
            """, limit)
        return [dict(r) for r in rows]

@router.get("/{execution_id}")
async def get_execution(execution_id: UUID):
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM executions WHERE id=$1", execution_id)
        return dict(row) if row else {}

@router.get("/{execution_id}/logs")
async def get_logs(execution_id: UUID, level: str = None, limit: int = 500):
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
