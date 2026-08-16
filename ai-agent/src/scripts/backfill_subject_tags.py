"""
scripts/backfill_subject_tags.py — Classifica por IA o assunto (subject_tag) de
chamadas já registradas na base antes da classificação em tempo real existir.

Idempotente: só processa chamadas com transcrição e ainda sem subject_tag —
pode ser rodado de novo com segurança (ex: se uma chamada falhar por erro
transitório do provedor de IA).

Uso (dentro do container ai-agent, que já tem as dependências e o INTERNAL_API_KEY):
  docker exec voipia-ai-agent python -m src.scripts.backfill_subject_tags
"""

import asyncio
import logging

from src.services import backend_client as bc
from src.services.ai_service import AIService
from src.services.subject_classifier import classify_subject

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("asteriskia.backfill_subject_tags")

_PAGE_SIZE = 50


async def main() -> None:
    ai = AIService()
    page = 0
    eligible = 0
    classified = 0

    while True:
        data = await bc.get("/api/v1/calls", params={"page": page, "size": _PAGE_SIZE})
        content = data.get("content", [])
        if not content:
            break

        for call in content:
            transcription = call.get("transcription")
            subject_tag = call.get("subjectTag")
            if not transcription or subject_tag:
                continue

            eligible += 1
            call_id = call["id"]
            call_type = call.get("callType")
            tag = await classify_subject(ai, transcription, call_type, log_ctx=str(call_id))
            if not tag:
                logger.warning("Chamada %s não pôde ser classificada — mantendo subject_tag vazio", call_id)
                continue

            await bc.patch(f"/api/v1/internal/calls/{call_id}/subject-tag", json={"subjectTag": tag})
            classified += 1
            logger.info("Chamada %s classificada: %r (tipo: %s)", call_id, tag, call_type)

        total_pages = data.get("totalPages", 1)
        if page >= total_pages - 1:
            break
        page += 1

    logger.info("Backfill concluído: %d chamada(s) elegível(is), %d classificada(s) com sucesso", eligible, classified)


if __name__ == "__main__":
    asyncio.run(main())
