"""
agents-platform/backend/main.py
FastAPI backend para a plataforma de agentes AsteriskIA
"""
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from contextlib import asynccontextmanager
import asyncio, json, os
from pathlib import Path
from datetime import datetime

from database import init_db, get_db
from models import *
from routers import agents, servers, executions, reports, knowledge
from scheduler import AgentScheduler

scheduler = AgentScheduler()

@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    await scheduler.start()
    yield
    await scheduler.stop()

app = FastAPI(title="AsteriskIA Agents Platform", version="1.0.0", lifespan=lifespan)

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

app.include_router(agents.router,     prefix="/api/agents",     tags=["agents"])
app.include_router(servers.router,    prefix="/api/servers",    tags=["servers"])
app.include_router(executions.router, prefix="/api/executions", tags=["executions"])
app.include_router(reports.router,    prefix="/api/reports",    tags=["reports"])
app.include_router(knowledge.router,  prefix="/api/knowledge",  tags=["knowledge"])

# WebSocket para logs em tempo real
ws_clients: dict[str, list[WebSocket]] = {}

@app.websocket("/ws/agent/{agent_id}/logs")
async def agent_logs_ws(websocket: WebSocket, agent_id: str):
    await websocket.accept()
    ws_clients.setdefault(agent_id, []).append(websocket)
    try:
        while True:
            await asyncio.sleep(1)
    except WebSocketDisconnect:
        ws_clients[agent_id].remove(websocket)

async def broadcast_log(agent_id: str, log: dict):
    for ws in ws_clients.get(agent_id, []):
        try:
            await ws.send_json(log)
        except Exception:
            pass

app.state.broadcast_log = broadcast_log
app.state.scheduler = scheduler
