"""
evaluation_llm.py — Avaliação de uma chamada contra a ficha de qualidade ativa.

Chamada Gemini separada da análise geral (insights_llm.py) — recebe a transcrição
diarizada + as perguntas/pesos da ficha ativa e devolve uma nota (0 a nota_maxima)
e justificativa por item, ancorada em trecho da transcrição (mesmo princípio de
insights_llm.py). Separação deliberada: no futuro, uma reavaliação com ficha nova
não precisa refazer STT/análise geral, só esta chamada.

Nota/justificativa aqui são só o INSUMO — nota_total ponderada, clamp em
[0, nota_maxima] e a regra de auto-fail são sempre calculados no backend Java
(EvaluationService), nunca aceitos prontos do LLM (mesma lição do bug real de
overflow em aderencia_script).
"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass

from google.genai import types as t

from src.gemini_client import get_client
from src.stt_diarize import TranscriptSegment
from src.token_usage import TokenUsage, extract_usage

logger = logging.getLogger("asteriskia.insights.evaluation_llm")


@dataclass(frozen=True)
class ScorecardItemInput:
    item_id: int
    pergunta: str
    nota_maxima: int
    is_critical: bool


@dataclass(frozen=True)
class EvaluatedItem:
    item_id: int
    nota: float
    justificativa: str | None
    trecho_referencia: str | None


@dataclass(frozen=True)
class EvaluationResult:
    items: list[EvaluatedItem]
    usage: TokenUsage | None
    model_id: str


def _response_schema(items: list[ScorecardItemInput]) -> t.Schema:
    return t.Schema(
        type=t.Type.OBJECT,
        properties={
            "items": t.Schema(
                type=t.Type.ARRAY,
                items=t.Schema(
                    type=t.Type.OBJECT,
                    properties={
                        "item_id": t.Schema(type=t.Type.INTEGER),
                        "nota": t.Schema(type=t.Type.NUMBER),
                        "justificativa": t.Schema(type=t.Type.STRING),
                        "trecho_referencia": t.Schema(type=t.Type.STRING),
                    },
                    required=["item_id", "nota", "justificativa"],
                ),
            ),
        },
        required=["items"],
    )


def _format_transcript(segments: list[TranscriptSegment]) -> str:
    label_by_speaker = {"agente": "Atendente", "cliente": "Cliente", "indefinido": "Locutor"}
    return "\n".join(f"{label_by_speaker.get(seg.speaker, 'Locutor')}: {seg.text}" for seg in segments)


def _build_prompt(segments: list[TranscriptSegment], items: list[ScorecardItemInput]) -> str:
    transcript = _format_transcript(segments)
    perguntas = "\n".join(
        f"- item_id={item.item_id} (nota de 0 a {item.nota_maxima}"
        f"{', CRÍTICO — nota 0 reprova a chamada inteira' if item.is_critical else ''}): {item.pergunta}"
        for item in items
    )
    return (
        "Você é um avaliador sênior de qualidade de atendimento ao cliente. Avalie a "
        "transcrição de chamada abaixo contra CADA item da ficha de avaliação a seguir, "
        "atribuindo uma nota dentro da faixa indicada e uma justificativa objetiva em "
        "português do Brasil, ancorada em um trecho literal da transcrição sempre que "
        "possível.\n\n"
        f"--- ITENS DA FICHA ---\n{perguntas}\n--- FIM DOS ITENS ---\n\n"
        f"--- TRANSCRIÇÃO ---\n{transcript}\n--- FIM DA TRANSCRIÇÃO ---\n\n"
        "Responda com uma nota para TODOS os item_id listados acima, sem omitir nenhum."
    )


def _generate_sync(segments: list[TranscriptSegment], items: list[ScorecardItemInput], model_id: str) -> tuple[str, object]:
    prompt = _build_prompt(segments, items)
    resp = get_client().models.generate_content(
        model=model_id,
        contents=prompt,
        config=t.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=_response_schema(items),
        ),
    )
    return resp.text or "{}", resp


async def evaluate_call(
    segments: list[TranscriptSegment],
    items: list[ScorecardItemInput],
    model_id: str,
) -> EvaluationResult:
    raw_text, resp = await asyncio.to_thread(_generate_sync, segments, items, model_id)
    usage = extract_usage(resp)

    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError as e:
        logger.error("Resposta do Gemini não é JSON válido para avaliação: %s", e)
        parsed = {}

    valid_item_ids = {item.item_id for item in items}
    evaluated: list[EvaluatedItem] = []
    for raw_item in parsed.get("items", []):
        item_id = raw_item.get("item_id")
        if item_id not in valid_item_ids:
            logger.warning("Avaliação retornou item_id desconhecido (%s) — ignorado", item_id)
            continue
        evaluated.append(EvaluatedItem(
            item_id=item_id,
            # Nota bruta do LLM, sem clamp — o backend Java (EvaluationService)
            # é quem clampa em [0, nota_maxima] antes de persistir.
            nota=float(raw_item.get("nota", 0.0)),
            justificativa=raw_item.get("justificativa"),
            trecho_referencia=raw_item.get("trecho_referencia"),
        ))

    return EvaluationResult(items=evaluated, usage=usage, model_id=model_id)
