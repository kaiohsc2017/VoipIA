"""routers/knowledge.py — base de conhecimento (PDFs)"""
import asyncio
import io
import uuid

from fastapi import APIRouter, UploadFile, File, HTTPException
from database import DB

router = APIRouter()

# Limite de tamanho do PDF — evita consumo excessivo de memória/CPU na extração
_MAX_UPLOAD_BYTES = 20 * 1024 * 1024  # 20 MB


def _extract_pdf_text(data: bytes) -> str:
    """Extração de texto do PDF — roda em thread pois é CPU-bound e bloqueia o event loop."""
    try:
        import pypdf
        reader = pypdf.PdfReader(io.BytesIO(data))
        return "\n".join(p.extract_text() or "" for p in reader.pages)
    except Exception:
        return data.decode("utf-8", errors="ignore")

@router.get("/")
async def list_docs(limit: int = 100, offset: int = 0):
    async with DB() as db:
        rows  = await db.fetch(
            "SELECT id,filename,title,tags,created_at FROM knowledge_docs ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            limit, offset)
        total = await db.fetchval("SELECT COUNT(*) FROM knowledge_docs")
        return {"items": [dict(r) for r in rows], "total": total, "limit": limit, "offset": offset}

@router.post("/upload")
async def upload_doc(file: UploadFile = File(...), tags: str = ""):
    if not file.filename.endswith(".pdf"):
        raise HTTPException(400, "Apenas PDFs aceitos")
    data = await file.read()
    if len(data) > _MAX_UPLOAD_BYTES:
        raise HTTPException(413, f"Arquivo excede o limite de {_MAX_UPLOAD_BYTES // (1024*1024)} MB")
    text = await asyncio.to_thread(_extract_pdf_text, data)
    tag_list = [t.strip() for t in tags.split(",") if t.strip()]
    async with DB() as db:
        row = await db.fetchrow("""
            INSERT INTO knowledge_docs (filename, title, content, tags)
            VALUES ($1,$2,$3,$4) RETURNING id, filename, title, tags, created_at
        """, file.filename, file.filename.replace(".pdf",""), text, tag_list)
        return dict(row)

@router.delete("/{doc_id}")
async def delete_doc(doc_id: uuid.UUID):
    async with DB() as db:
        await db.execute("DELETE FROM knowledge_docs WHERE id=$1", doc_id)
        return {"ok": True}

@router.get("/search")
async def search_knowledge(q: str, limit: int = 5):
    async with DB() as db:
        rows = await db.fetch("""
            SELECT id, filename, title, tags,
                   LEFT(content, 500) as excerpt,
                   similarity(content, $1) as score
            FROM knowledge_docs WHERE content % $1
            ORDER BY score DESC LIMIT $2
        """, q, limit)
        return [dict(r) for r in rows]
