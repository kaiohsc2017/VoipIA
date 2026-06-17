"""routers/servers.py"""
from fastapi import APIRouter, HTTPException
from uuid import UUID
from models import ServerCreate
from database import DB
import asyncssh, json

router = APIRouter()

@router.get("/")
async def list_servers(limit: int = 100, offset: int = 0):
    async with DB() as db:
        rows  = await db.fetch(
            "SELECT id,name,host,port,username,auth_type,tags,active,created_at FROM servers ORDER BY name LIMIT $1 OFFSET $2",
            limit, offset)
        total = await db.fetchval("SELECT COUNT(*) FROM servers")
        return {"items": [dict(r) for r in rows], "total": total, "limit": limit, "offset": offset}

@router.post("/")
async def create_server(body: ServerCreate):
    async with DB() as db:
        row = await db.fetchrow("""
            INSERT INTO servers (name,host,port,username,auth_type,password,ssh_key,tags)
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *
        """, body.name, body.host, body.port, body.username,
             body.auth_type, body.password, body.ssh_key, body.tags)
        return dict(row)

@router.put("/{server_id}")
async def update_server(server_id: UUID, body: ServerCreate):
    async with DB() as db:
        row = await db.fetchrow("""
            UPDATE servers SET name=$1,host=$2,port=$3,username=$4,
                auth_type=$5,password=$6,ssh_key=$7,tags=$8
            WHERE id=$9 RETURNING *
        """, body.name, body.host, body.port, body.username,
             body.auth_type, body.password, body.ssh_key, body.tags, server_id)
        if not row: raise HTTPException(404)
        return dict(row)

@router.delete("/{server_id}")
async def delete_server(server_id: UUID):
    async with DB() as db:
        await db.execute("DELETE FROM servers WHERE id=$1", server_id)
        return {"ok": True}

@router.post("/{server_id}/test")
async def test_connection(server_id: UUID):
    async with DB() as db:
        row = await db.fetchrow("SELECT * FROM servers WHERE id=$1", server_id)
        if not row: raise HTTPException(404)
    try:
        kwargs = dict(host=row["host"], port=row["port"], username=row["username"],
                      known_hosts=None, connect_timeout=10)
        if row["auth_type"] == "key" and row["ssh_key"]:
            kwargs["client_keys"] = [asyncssh.import_private_key(row["ssh_key"])]
        else:
            kwargs["password"] = row["password"]
        async with asyncssh.connect(**kwargs) as conn:
            result = await conn.run("echo ok && uname -a", check=True)
            return {"ok": True, "output": result.stdout.strip()}
    except Exception as e:
        return {"ok": False, "error": str(e)}
