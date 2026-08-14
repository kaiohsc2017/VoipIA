"""
embedding_server.py — Servidor HTTP interno de embeddings (Fase 25 — base de
conhecimento/RAG do chat do Call Center).

Roda DENTRO do container `insights` (mesmo processo do loop de polling,
servido concorrentemente via asyncio.gather em main.py), sem porta publicada
ao host — só acessível pela rede interna docker (172.16.7.18:8000), chamado
pelo backend Java quando um artigo da base de conhecimento é indexado ou uma
pergunta do chat precisa ser comparada contra os artigos existentes.

Autenticação: mesmo esquema de "chave interna" já usado em
`backend_client.py`/`X-Internal-Key` — nunca expõe este endpoint sem a chave.
"""

from __future__ import annotations

import logging
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

logger = logging.getLogger("asteriskia.insights.embedding_server")

app = FastAPI(title="AsteriskIA Insights — Embedding Server")

# Modelo carregado uma única vez, no nível de módulo (não lazy): é aceitável
# aqui porque (1) o modelo é pequeno (~118M parâmetros,
# paraphrase-multilingual-MiniLM-L12-v2 — cobre português) e (2) este
# container é de longa duração (já roda o loop de polling desde sempre),
# diferente de um ambiente serverless/lambda, onde carregar no import
# penalizaria todo cold start. Carregar aqui garante que o healthcheck do
# container só fica "healthy" depois do modelo já estar pronto em memória.
_model = SentenceTransformer("paraphrase-multilingual-MiniLM-L12-v2")

EMBEDDING_DIMENSIONS = 384


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    vector: list[float]


def _verify_internal_key(x_internal_key: str | None = Header(default=None)) -> None:
    """Confere o header X-Internal-Key contra INTERNAL_API_KEY do ambiente.

    Nunca loga o valor da chave (nem a recebida, nem a esperada) — só o
    resultado da comparação, para não vazar segredo em log de erro.
    """
    expected = os.environ.get("INTERNAL_API_KEY")
    if not expected or x_internal_key != expected:
        logger.warning("Tentativa de acesso a /internal/embed com X-Internal-Key inválida ou ausente")
        raise HTTPException(status_code=401, detail="Não autorizado")


@app.post("/internal/embed", response_model=EmbedResponse, dependencies=[Depends(_verify_internal_key)])
async def embed(payload: EmbedRequest) -> EmbedResponse:
    """Gera o vetor de embedding (384 dimensões) de um texto.

    Usado tanto para indexar um artigo novo da base de conhecimento quanto
    para comparar a pergunta do cliente no chat contra os artigos já
    indexados (busca de similaridade feita do lado do Java/Postgres, via
    pgvector).
    """
    text = (payload.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="Campo 'text' não pode ser vazio")

    # normalize_embeddings=True é necessário para o pgvector calcular
    # corretamente a distância cosseno (operador `<=>`, vector_cosine_ops)
    # do lado do Java/Postgres — sem normalizar, a comparação de vetores não
    # equivale à similaridade de cosseno esperada.
    vector = _model.encode(text, normalize_embeddings=True)

    return EmbedResponse(vector=vector.tolist())
