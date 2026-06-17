"""routers/agents.py"""
from fastapi import APIRouter, HTTPException, Request
from uuid import UUID
from models import AgentCreate, AgentOut
from database import DB
import json, uuid
from datetime import datetime, timezone

router = APIRouter()

@router.get("/")
async def list_agents(limit: int = 100, offset: int = 0):
    async with DB() as db:
        rows  = await db.fetch(
            "SELECT * FROM agents ORDER BY created_at DESC LIMIT $1 OFFSET $2", limit, offset)
        total = await db.fetchval("SELECT COUNT(*) FROM agents")
        return {"items": [dict(r) for r in rows], "total": total, "limit": limit, "offset": offset}

@router.post("/", response_model=dict)
async def create_agent(body: AgentCreate):
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
        return dict(row)

@router.get("/{agent_id}")
async def get_agent(agent_id: UUID):
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM agents WHERE id=$1", agent_id)
        if not row: raise HTTPException(404, "Agente não encontrado")
        return dict(row)

@router.put("/{agent_id}")
async def update_agent(agent_id: UUID, body: AgentCreate, request: Request):
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
        scheduler.reload_agent(agent)
    except Exception as e:
        print(f"[agents] reload_agent error: {e}")
    return agent

@router.delete("/{agent_id}")
async def delete_agent(agent_id: UUID):
    async with DB() as db:
        await db.execute("DELETE FROM agents WHERE id=$1", agent_id)
        return {"ok": True}

@router.post("/{agent_id}/run")
async def run_agent_now(agent_id: UUID, request: Request):
    scheduler = request.app.state.scheduler
    result = await scheduler.run_now(str(agent_id))
    return result

@router.post("/{agent_id}/pause")
async def pause_agent(agent_id: UUID):
    async with DB() as db:
        await db.execute("UPDATE agents SET status='paused' WHERE id=$1", agent_id)
        return {"ok": True}

@router.post("/{agent_id}/resume")
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
