"""
report_narrative_llm.py — Narrativa do relatório de performance por atendente.

Recebe o agregado numérico JÁ CALCULADO no backend Java (nota média, nota por
item, achados por tipo, achados mais graves) e a evolução em relação ao
relatório anterior (também já calculada) e devolve só texto: pontos fortes,
pontos de melhoria, recomendações e comparação textual (Fase 2 do Quality
Management, V39). O LLM nunca recebe transcrições brutas nem recalcula
número nenhum — mesmo princípio de evaluation_llm.py.
"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass

from google.genai import types as t

from src.gemini_client import get_client
from src.token_usage import TokenUsage, extract_usage

logger = logging.getLogger("asteriskia.insights.report_narrative_llm")


@dataclass(frozen=True)
class NarrativeResult:
    pontos_fortes: list[str]
    pontos_melhoria: list[str]
    recomendacoes: list[str]
    comparacao_textual: str | None
    usage: TokenUsage | None
    model_id: str


def _response_schema() -> t.Schema:
    return t.Schema(
        type=t.Type.OBJECT,
        properties={
            "pontosFortes": t.Schema(type=t.Type.ARRAY, items=t.Schema(type=t.Type.STRING)),
            "pontosMelhoria": t.Schema(type=t.Type.ARRAY, items=t.Schema(type=t.Type.STRING)),
            "recomendacoes": t.Schema(type=t.Type.ARRAY, items=t.Schema(type=t.Type.STRING)),
            "comparacaoTextual": t.Schema(type=t.Type.STRING),
        },
        required=["pontosFortes", "pontosMelhoria", "recomendacoes"],
    )


def _build_prompt(agent_name: str, date_from: str, date_to: str, aggregate: dict, evolution: dict | None) -> str:
    parts = [
        "Você é um coach sênior de qualidade de atendimento ao cliente. Escreva uma "
        "avaliação de performance objetiva e construtiva em português do Brasil para o "
        f"atendente '{agent_name}', referente ao período de {date_from} a {date_to}, "
        "com base SOMENTE nos dados agregados abaixo (já calculados — não invente "
        "números, apenas comente-os).\n",
        f"--- AGREGADO DO PERÍODO ---\n{json.dumps(aggregate, ensure_ascii=False, indent=2)}\n",
    ]
    if evolution:
        parts.append(
            "--- EVOLUÇÃO EM RELAÇÃO AO RELATÓRIO ANTERIOR (delta já calculado) ---\n"
            f"{json.dumps(evolution, ensure_ascii=False, indent=2)}\n"
            "Use este delta para escrever 'comparacaoTextual' — descreva a evolução em "
            "prosa, sem recalcular nem citar números que não estejam aqui.\n"
        )
    else:
        parts.append("Não há relatório anterior para comparação — omita ou deixe 'comparacaoTextual' vazio.\n")
    parts.append(
        "Responda com: pontosFortes (lista curta), pontosMelhoria (lista curta, priorizada "
        "pelos itens/achados com pior desempenho), recomendacoes (ações concretas e "
        "acionáveis) e comparacaoTextual (só se houver evolução)."
    )
    return "\n".join(parts)


def _generate_sync(prompt: str, model_id: str) -> tuple[str, object]:
    resp = get_client().models.generate_content(
        model=model_id,
        contents=prompt,
        config=t.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=_response_schema(),
        ),
    )
    return resp.text or "{}", resp


async def generate_narrative(
    agent_name: str, date_from: str, date_to: str, aggregate: dict, evolution: dict | None, model_id: str
) -> NarrativeResult:
    prompt = _build_prompt(agent_name, date_from, date_to, aggregate, evolution)
    raw_text, resp = await asyncio.to_thread(_generate_sync, prompt, model_id)
    usage = extract_usage(resp)

    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError as e:
        logger.error("Resposta do Gemini não é JSON válido para narrativa de relatório: %s", e)
        parsed = {}

    return NarrativeResult(
        pontos_fortes=list(parsed.get("pontosFortes", [])),
        pontos_melhoria=list(parsed.get("pontosMelhoria", [])),
        recomendacoes=list(parsed.get("recomendacoes", [])),
        comparacao_textual=parsed.get("comparacaoTextual"),
        usage=usage,
        model_id=model_id,
    )
