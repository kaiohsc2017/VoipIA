"""
agents-platform/backend/main.py
FastAPI backend — VoipIA Agentes
"""
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager
from jose import jwt as _jwt, JWTError
import asyncio, json, os

from database import init_db
from routers import agents, servers, executions, reports, knowledge
from routers import llm_config
from routers import system
from scheduler import AgentScheduler

scheduler = AgentScheduler()

# ── JWT ──────────────────────────────────────────────────────────────────────
# Replica exatamente a lógica de padding do JwtService.java:
# key = secret.getBytes(); padded = new byte[max(32, key.length)]
_JWT_RAW = os.getenv("BACKEND_JWT_SECRET", "changeme_dev_secret").encode()
JWT_KEY  = _JWT_RAW.ljust(max(32, len(_JWT_RAW)), b"\x00")

# Rotas acessíveis sem autenticação
_PUBLIC = (
    "/",
    "/api/llm/status",
    "/api/llm/providers",
    "/api/system/health",
)
_PUBLIC_PREFIX = ("/ws/",)

def _is_public(path: str) -> bool:
    if path in _PUBLIC:
        return True
    return any(path.startswith(p) for p in _PUBLIC_PREFIX)

# WebSocket: canal por agent_id + canal global __alerts__
ws_clients: dict[str, list[WebSocket]] = {}

async def broadcast(channel: str, data: dict):
    """Envia para todos os WebSocket inscritos no canal, removendo conexões mortas."""
    dead: list = []
    for ws in ws_clients.get(channel, []):
        try:
            await ws.send_json(data)
        except Exception:
            dead.append(ws)
    for ws in dead:
        try:
            ws_clients[channel].remove(ws)
        except (ValueError, KeyError):
            pass
    # Alertas também vão para o canal global da UI
    if data.get("level") in ("error", "warning") and channel != "__alerts__":
        dead = []
        for ws in ws_clients.get("__alerts__", []):
            try:
                await ws.send_json(data)
            except Exception:
                dead.append(ws)
        for ws in dead:
            try:
                ws_clients["__alerts__"].remove(ws)
            except (ValueError, KeyError):
                pass

@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()   # apenas pool — schema já aplicado pelo migrate.py
    scheduler.set_broadcast(broadcast)
    await scheduler.start()
    yield
    await scheduler.stop()


# Swagger/OpenAPI expõem o schema completo da API (inclusive rotas de secrets/
# servers) — desligados por padrão em produção; habilitar só em dev via env.
_ENABLE_DOCS = os.getenv("AGENTS_ENABLE_DOCS", "false").lower() == "true"

app = FastAPI(
    title="VoipIA Agents Platform", version="2.0.0", lifespan=lifespan,
    docs_url="/docs" if _ENABLE_DOCS else None,
    redoc_url="/redoc" if _ENABLE_DOCS else None,
    openapi_url="/openapi.json" if _ENABLE_DOCS else None,
)

# CORS — restrito às origens configuradas. O frontend é same-origin
# (servido pelo mesmo domínio via Caddy), então a lista padrão cobre produção.
_CORS_ORIGINS = [
    o.strip()
    for o in os.getenv("BACKEND_ALLOWED_ORIGINS", "https://app.voiphash.com.br").split(",")
    if o.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_CORS_ORIGINS,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Middleware JWT ────────────────────────────────────────────────────────────
@app.middleware("http")
async def jwt_middleware(request: Request, call_next):
    if _is_public(request.url.path):
        return await call_next(request)
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return JSONResponse({"detail": "Não autenticado"}, status_code=401)
    token = auth[7:]
    try:
        payload = _jwt.decode(token, JWT_KEY, algorithms=["HS256"])
        request.state.user = payload.get("sub", "")
        request.state.role = payload.get("role", "USER")
        request.state.perms = payload.get("perm", {})
        # Rejeita tokens de 2FA em etapa pendente
        if payload.get("totp_pending"):
            return JSONResponse({"detail": "2FA pendente"}, status_code=401)
    except JWTError:
        return JSONResponse({"detail": "Token inválido ou expirado"}, status_code=401)
    return await call_next(request)

app.include_router(agents.router,     prefix="/api/agents",     tags=["agents"])
app.include_router(servers.router,    prefix="/api/servers",    tags=["servers"])
app.include_router(executions.router, prefix="/api/executions", tags=["executions"])
app.include_router(reports.router,    prefix="/api/reports",    tags=["reports"])
app.include_router(knowledge.router,  prefix="/api/knowledge",  tags=["knowledge"])
app.include_router(llm_config.router, prefix="/api/llm",        tags=["llm"])
app.include_router(system.router,     prefix="/api/system",     tags=["system"])

def _ws_auth(token: str | None) -> bool:
    """Valida JWT no handshake do WebSocket (query param ?token=).

    Achado de segurança (débito aceito): o JWT principal (8h de validade)
    trafegando na query string do WebSocket ficava exposto em logs de acesso
    e histórico do browser. Agora só aceita o token de streaming de vida
    curta (60s, claim scope=stream) emitido por POST /api/v1/auth/streaming-token
    no backend Java — o JWT principal colado na URL não passa mais aqui.
    """
    if not token:
        return False
    try:
        payload = _jwt.decode(token, JWT_KEY, algorithms=["HS256"])
        return payload.get("scope") == "stream" and not payload.get("totp_pending", False)
    except JWTError:
        return False

@app.websocket("/ws/agent/{agent_id}/logs")
async def agent_logs_ws(websocket: WebSocket, agent_id: str, token: str | None = None):
    """WebSocket para logs em tempo real de um agente específico."""
    if not _ws_auth(token):
        await websocket.close(code=4401, reason="Não autenticado")
        return
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
async def alerts_ws(websocket: WebSocket, token: str | None = None):
    """WebSocket global para alertas em tempo real na UI."""
    if not _ws_auth(token):
        await websocket.close(code=4401, reason="Não autenticado")
        return
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
