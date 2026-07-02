"""routers/servers.py"""
from fastapi import APIRouter, HTTPException, Depends, Query
from uuid import UUID
from models import ServerCreate
from database import DB
from executor import _build_ssh_kwargs
from auth import require_permission
import asyncssh, json

router = APIRouter()

# Cadastro de servidor guarda credenciais SSH usadas pelos agentes — escrita e
# teste de conexão exigem PERM_WRITE_agents.servers (ou ADMIN legado).
# Leitura (sem password/ssh_key) fica aberta.
_WRITE = [Depends(require_permission("agents.servers", "write"))]

@router.get("/")
async def list_servers(limit: int = Query(default=100, le=500), offset: int = 0):
    async with DB() as db:
        rows  = await db.fetch(
            "SELECT id,name,host,port,username,auth_type,tags,active,created_at FROM servers ORDER BY name LIMIT $1 OFFSET $2",
            limit, offset)
        total = await db.fetchval("SELECT COUNT(*) FROM servers")
        return {"items": [dict(r) for r in rows], "total": total, "limit": limit, "offset": offset}

# Colunas seguras para retornar ao cliente — nunca expõe password/ssh_key.
_SAFE_COLS = "id,name,host,port,username,auth_type,tags,active,created_at"

@router.post("/", dependencies=_WRITE)
async def create_server(body: ServerCreate):
    async with DB() as db:
        row = await db.fetchrow(f"""
            INSERT INTO servers (name,host,port,username,auth_type,password,ssh_key,tags)
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING {_SAFE_COLS}
        """, body.name, body.host, body.port, body.username,
             body.auth_type, body.password, body.ssh_key, body.tags)
        return dict(row)

@router.put("/{server_id}", dependencies=_WRITE)
async def update_server(server_id: UUID, body: ServerCreate):
    async with DB() as db:
        row = await db.fetchrow(f"""
            UPDATE servers SET name=$1,host=$2,port=$3,username=$4,
                auth_type=$5,password=$6,ssh_key=$7,tags=$8
            WHERE id=$9 RETURNING {_SAFE_COLS}
        """, body.name, body.host, body.port, body.username,
             body.auth_type, body.password, body.ssh_key, body.tags, server_id)
        if not row: raise HTTPException(404)
        return dict(row)

@router.delete("/{server_id}", dependencies=_WRITE)
async def delete_server(server_id: UUID):
    async with DB() as db:
        await db.execute("DELETE FROM servers WHERE id=$1", server_id)
        return {"ok": True}

@router.post("/{server_id}/test", dependencies=_WRITE)
async def test_connection(server_id: UUID):
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM servers WHERE id=$1", server_id)
        if not row: raise HTTPException(404)
    try:
        srv = dict(row)
        srv["connect_timeout"] = 10
        async with asyncssh.connect(**_build_ssh_kwargs(srv)) as conn:
            result = await conn.run("echo ok && uname -a", check=True)
            return {"ok": True, "output": result.stdout.strip()}
    except Exception as e:
        return {"ok": False, "error": str(e)}
