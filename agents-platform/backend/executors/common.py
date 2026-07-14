"""
executors/common.py — helpers compartilhados por todos os executors (fase 23,
O3.4 da refatoração, extraído de executor.py): construção de kwargs SSH, log de
execução, memória coletiva dos agentes e fallback de IA.
"""
import json
import logging
import os
from uuid import UUID

import asyncssh

from database import DB
from llm import ask as llm_ask

logger = logging.getLogger("asteriskia.executor")

_SSH_KNOWN_HOSTS = os.environ.get("SSH_KNOWN_HOSTS_FILE", "")


def _build_ssh_kwargs(server: dict) -> dict:
    """Monta kwargs para asyncssh.connect() centralizando host key checking."""
    kwargs: dict = dict(
        host=server["host"],
        port=server["port"],
        username=server["username"],
        known_hosts=_SSH_KNOWN_HOSTS or None,
        connect_timeout=15,
    )
    if not _SSH_KNOWN_HOSTS:
        logger.warning(
            "SSH sem verificação de host key para %s — defina SSH_KNOWN_HOSTS_FILE no .env",
            server["host"],
        )
    if server.get("auth_type") == "key" and server.get("ssh_key"):
        kwargs["client_keys"] = [asyncssh.import_private_key(server["ssh_key"])]
    else:
        kwargs["password"] = server.get("password", "")
    return kwargs

# ─── Helpers ──────────────────────────────────────────────────────────────────

async def log(db, execution_id: UUID, agent_id: UUID, level: str,
              message: str, server: str = None, raw: str = None):
    await db.execute("""
        INSERT INTO execution_logs (execution_id, agent_id, level, server, message, raw_output)
        VALUES ($1,$2,$3,$4,$5,$6)
    """, execution_id, agent_id, level, server, message, raw)

# ─── Memória + base de conhecimento ──────────────────────────────────────────

async def memory_recall(agent_id: UUID, query: str) -> str:
    """Busca memória própria + de outros agentes + base de conhecimento."""
    async with DB() as db:
        own = await db.fetch("""
            SELECT title, content, mtype FROM agent_memory
            WHERE agent_id=$1 AND (content % $2 OR title % $2)
            ORDER BY similarity(content,$2) DESC LIMIT 5
        """, agent_id, query)

        shared = await db.fetch("""
            SELECT am.title, am.content, am.mtype, a.name as agent_name
            FROM agent_memory am JOIN agents a ON a.id=am.agent_id
            WHERE am.agent_id!=$1 AND (am.content % $2 OR am.title % $2)
            ORDER BY am.usefulness DESC, similarity(am.content,$2) DESC LIMIT 3
        """, agent_id, query)

        docs = await db.fetch("""
            SELECT filename, LEFT(content,600) as excerpt
            FROM knowledge_docs WHERE content % $1
            ORDER BY similarity(content,$1) DESC LIMIT 2
        """, query)

    parts = []
    if own:
        parts.append("### Memória própria\n" + "\n".join(
            f"- [{r['mtype']}] {r['title']}: {r['content'][:200]}" for r in own))
    if shared:
        parts.append("### Memória de outros agentes\n" + "\n".join(
            f"- [{r['agent_name']}] {r['title']}: {r['content'][:200]}" for r in shared))
    if docs:
        parts.append("### Base de conhecimento\n" + "\n".join(
            f"- {r['filename']}: {r['excerpt']}" for r in docs))
    return "\n\n".join(parts)

async def memory_save(agent_id: UUID, mtype: str, title: str, content: str,
                      metadata: dict | None = None, tags: list | None = None):
    if metadata is None:
        metadata = {}
    if tags is None:
        tags = []
    async with DB() as db:
        await db.execute("""
            INSERT INTO agent_memory (agent_id,mtype,title,content,metadata,tags)
            VALUES ($1,$2,$3,$4,$5,$6)
            ON CONFLICT DO NOTHING
        """, agent_id, mtype, title, content, json.dumps(metadata), tags)

# ─── Fallback IA (Gemini) ─────────────────────────────────────────────────────

async def ai_fallback(skill: str, problem: str, memory_ctx: str, check_output: str) -> str:
    """
    Fallback de IA — delega ao módulo llm.py que suporta múltiplos provedores.
    Provedor, modelo e habilitação são configurados via variáveis de ambiente.
    """
    return await llm_ask(skill=skill, problem=problem,
                         memory_ctx=memory_ctx, check_output=check_output)
