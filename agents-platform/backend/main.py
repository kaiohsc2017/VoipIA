"""
agents-platform/backend/main.py
FastAPI backend — AsteriskIA Agentes
"""
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager
import asyncio, json

from database import init_db
from routers import agents, servers, executions, reports, knowledge
from routers import llm_config
from scheduler import AgentScheduler

scheduler = AgentScheduler()

# WebSocket: canal por agent_id + canal global __alerts__
ws_clients: dict[str, list[WebSocket]] = {}

async def broadcast(channel: str, data: dict):
    """Envia para todos os WebSocket inscritos no canal."""
    for ws in ws_clients.get(channel, []):
        try:
            await ws.send_json(data)
        except Exception:
            pass
    # Alertas também vão para o canal global da UI
    if data.get("level") in ("error", "warning") and channel != "__alerts__":
        for ws in ws_clients.get("__alerts__", []):
            try:
                await ws.send_json(data)
            except Exception:
                pass

@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    scheduler.set_broadcast(broadcast)
    await scheduler.start()
    yield
    await scheduler.stop()

app = FastAPI(title="AsteriskIA Agents Platform", version="2.0.0", lifespan=lifespan)

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

app.include_router(agents.router,     prefix="/api/agents",     tags=["agents"])
app.include_router(servers.router,    prefix="/api/servers",    tags=["servers"])
app.include_router(executions.router, prefix="/api/executions", tags=["executions"])
app.include_router(reports.router,    prefix="/api/reports",    tags=["reports"])
app.include_router(knowledge.router,  prefix="/api/knowledge",  tags=["knowledge"])
app.include_router(llm_config.router, prefix="/api/llm",       tags=["llm"])

@app.websocket("/ws/agent/{agent_id}/logs")
async def agent_logs_ws(websocket: WebSocket, agent_id: str):
    """WebSocket para logs em tempo real de um agente específico."""
    await websocket.accept()
    ws_clients.setdefault(agent_id, []).append(websocket)
    try:
        while True:
            await asyncio.sleep(30)
            await websocket.send_json({"ping": True})
    except WebSocketDisconnect:
        pass
    finally:
        if agent_id in ws_clients:
            try: ws_clients[agent_id].remove(websocket)
            except ValueError: pass

@app.websocket("/ws/alerts")
async def alerts_ws(websocket: WebSocket):
    """WebSocket global para alertas em tempo real na UI."""
    await websocket.accept()
    ws_clients.setdefault("__alerts__", []).append(websocket)
    try:
        while True:
            await asyncio.sleep(30)
            await websocket.send_json({"ping": True})
    except WebSocketDisconnect:
        pass
    finally:
        if "__alerts__" in ws_clients:
            try: ws_clients["__alerts__"].remove(websocket)
            except ValueError: pass

app.state.broadcast   = broadcast
app.state.scheduler   = scheduler
app.state.ws_clients  = ws_clients
