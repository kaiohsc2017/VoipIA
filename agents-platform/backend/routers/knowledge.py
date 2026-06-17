"""routers/knowledge.py — base de conhecimento (PDFs)"""
from fastapi import APIRouter, UploadFile, File, HTTPException
from database import DB
import uuid

router = APIRouter()

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
    # Extração de texto do PDF
    try:
        import pypdf, io
        reader = pypdf.PdfReader(io.BytesIO(data))
        text   = "\n".join(p.extract_text() or "" for p in reader.pages)
    except Exception:
        text = data.decode("utf-8", errors="ignore")
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
