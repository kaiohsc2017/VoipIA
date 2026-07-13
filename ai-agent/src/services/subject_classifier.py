"""
subject_classifier.py — Classificação do assunto de uma chamada via LLM.

Extraído de jira_call_flow.py para ser reaproveitado tanto em tempo real
(fim de cada chamada nova) quanto pelo backfill em lote de chamadas antigas
(scripts/backfill_subject_tags.py) — mesma lógica de prompt e de vocabulário
em ambos os casos, sem duplicação.
"""

import logging

from src.services.ai_service import AIService
from src.services import backend_client as bc

logger = logging.getLogger("asteriskia.subject_classifier")


async def classify_subject(ai: AIService, transcription: str, call_type: str | None, log_ctx: str = "") -> str | None:
    """
    Classifica o assunto de uma chamada (ex: "Reset de senha", "Lentidão de sistema")
    via LLM, best-effort — nunca lança para o chamador, só loga e retorna None.
    Reaproveita rótulos já usados para o mesmo tipo de chamada (busca no backend)
    para evitar sinônimos duplicados se acumulando ao longo do tempo.
    """
    if not transcription or not transcription.strip():
        return None
    try:
        existing_tags: list[str] = []
        if call_type:
            existing_tags = await bc.get(
                "/api/v1/internal/calls/subject-tags", params={"callType": call_type}
            )

        vocab_hint = (
            f"Rótulos já usados para este tipo de chamada: {', '.join(existing_tags)}. "
            "Reaproveite um deles se fizer sentido; só crie um novo se nenhum se aplicar."
            if existing_tags else
            "Ainda não há rótulos cadastrados para este tipo — crie um novo."
        )

        prompt = (
            "Classifique o assunto principal desta chamada de atendimento em uma "
            "etiqueta curta (2 a 4 palavras, em português, sem pontuação final). "
            f"{vocab_hint}\n\n"
            "Responda APENAS com a etiqueta, nada mais.\n\n"
            f"Transcrição:\n{transcription}"
        )
        raw = await ai.generate_response(prompt)
        tag = raw.strip().strip('"').strip("'").splitlines()[0].strip()
        return tag[:100] if tag else None
    except Exception as e:
        logger.warning("[%s] Falha ao classificar assunto da chamada (best-effort): %s", log_ctx, e)
        return None
