"""
insights_llm.py — Geração dos insights estruturados de uma chamada.

Uma única chamada ao Gemini recebe a transcrição diarizada completa + os
metadados da chamada (atendente, fila/skill, direção, duração) e retorna um
JSON estruturado: resumo, categoria, sentimento, aderência a script,
criticidade, e os achados (melhorias/falhas/treinamento/tendência) já
ancorados no trecho da transcrição que os embasa — mesmo formato consumido
por call_insights/call_insight_findings (V35).
"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass, field

from google.genai import types as t

from src.gemini_client import get_client
from src.stt_diarize import TranscriptSegment
from src.token_usage import TokenUsage, extract_usage

logger = logging.getLogger("asteriskia.insights.insights_llm")

_SENTIMENT_ENUM = ["muito_negativo", "negativo", "neutro", "positivo", "muito_positivo"]
_CRITICIDADE_ENUM = ["baixa", "media", "alta", "urgente"]
_PRIORIDADE_ENUM = ["baixa", "media", "alta"]

_FINDING_SCHEMA = t.Schema(
    type=t.Type.ARRAY,
    items=t.Schema(
        type=t.Type.OBJECT,
        properties={
            "descricao": t.Schema(type=t.Type.STRING),
            "trecho_referencia": t.Schema(type=t.Type.STRING),
            "prioridade": t.Schema(type=t.Type.STRING, enum=_PRIORIDADE_ENUM),
        },
        required=["descricao", "prioridade"],
    ),
)

_RESPONSE_SCHEMA = t.Schema(
    type=t.Type.OBJECT,
    properties={
        "resumo": t.Schema(type=t.Type.STRING),
        "categoria_assunto": t.Schema(type=t.Type.STRING),
        "sentimento_geral": t.Schema(type=t.Type.STRING, enum=_SENTIMENT_ENUM),
        "aderencia_script": t.Schema(type=t.Type.NUMBER),
        "criticidade": t.Schema(type=t.Type.STRING, enum=_CRITICIDADE_ENUM),
        "melhorias": _FINDING_SCHEMA,
        "falhas": _FINDING_SCHEMA,
        "treinamentos": _FINDING_SCHEMA,
        "tendencias": _FINDING_SCHEMA,
    },
    required=[
        "resumo", "categoria_assunto", "sentimento_geral",
        "aderencia_script", "criticidade", "melhorias", "falhas",
        "treinamentos", "tendencias",
    ],
)


@dataclass(frozen=True)
class Finding:
    tipo: str  # melhoria | falha | treinamento | tendencia
    descricao: str
    trecho_referencia: str | None
    prioridade: str


@dataclass(frozen=True)
class InsightsResult:
    resumo: str
    categoria_assunto: str
    sentimento_geral: str
    aderencia_script: float
    criticidade: str
    findings: list[Finding]
    insights_json: dict = field(repr=False)
    usage: TokenUsage | None
    model_id: str


def _format_transcript(segments: list[TranscriptSegment]) -> str:
    label_by_speaker = {"agente": "Atendente", "cliente": "Cliente", "indefinido": "Locutor"}
    lines = [f"{label_by_speaker.get(seg.speaker, 'Locutor')}: {seg.text}" for seg in segments]
    return "\n".join(lines)


def _build_prompt(segments: list[TranscriptSegment], skill: str | None, duration_seconds: int | None) -> str:
    transcript = _format_transcript(segments)
    contexto = []
    if skill:
        contexto.append(f"Fila/departamento da chamada: {skill}.")
    if duration_seconds:
        contexto.append(f"Duração total: {duration_seconds} segundos.")
    contexto_str = " ".join(contexto)

    return (
        "Você é um analista sênior de qualidade de atendimento ao cliente. Analise a "
        "transcrição de chamada abaixo e produza uma análise estruturada em português "
        f"do Brasil. {contexto_str}\n\n"
        "Para cada achado (melhoria, falha, treinamento, tendência), ancore a descrição "
        "em um trecho literal da transcrição (trecho_referencia) sempre que possível.\n"
        "- melhorias: sugestões concretas de melhoria no atendimento ou processo.\n"
        "- falhas: falhas de processo identificadas (ex: informação incorreta dada ao "
        "cliente, promessa não registrada, script não seguido).\n"
        "- treinamentos: oportunidades de treinamento/coaching específicas para o atendente.\n"
        "- tendencias: qualquer padrão que pareça recorrente ou sinalize uma tendência maior "
        "(mesmo que baseado só nesta chamada).\n"
        "Marque criticidade='urgente' apenas se houver risco real de perda do cliente, "
        "ameaça de reclamação formal, ou situação que exija revisão humana imediata.\n\n"
        f"--- TRANSCRIÇÃO ---\n{transcript}\n--- FIM DA TRANSCRIÇÃO ---"
    )


def _generate_sync(segments: list[TranscriptSegment], skill: str | None,
                    duration_seconds: int | None, model_id: str) -> tuple[str, object]:
    prompt = _build_prompt(segments, skill, duration_seconds)
    resp = get_client().models.generate_content(
        model=model_id,
        contents=prompt,
        config=t.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=_RESPONSE_SCHEMA,
        ),
    )
    return resp.text or "{}", resp


async def generate_insights(
    segments: list[TranscriptSegment],
    skill: str | None,
    duration_seconds: int | None,
    model_id: str,
) -> InsightsResult:
    raw_text, resp = await asyncio.to_thread(_generate_sync, segments, skill, duration_seconds, model_id)
    usage = extract_usage(resp)

    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError as e:
        logger.error("Resposta do Gemini não é JSON válido para insights: %s", e)
        parsed = {}

    findings: list[Finding] = []
    for tipo in ("melhoria", "falha", "treinamento", "tendencia"):
        chave = {"melhoria": "melhorias", "falha": "falhas",
                 "treinamento": "treinamentos", "tendencia": "tendencias"}[tipo]
        for item in parsed.get(chave, []):
            findings.append(Finding(
                tipo=tipo,
                descricao=item.get("descricao", ""),
                trecho_referencia=item.get("trecho_referencia"),
                prioridade=item.get("prioridade", "media"),
            ))

    return InsightsResult(
        resumo=parsed.get("resumo", ""),
        categoria_assunto=parsed.get("categoria_assunto", ""),
        sentimento_geral=parsed.get("sentimento_geral", "neutro"),
        # Achado em produção: o schema pede "0 a 1" no prompt, mas response_schema não
        # tem como forçar faixa numérica — o Gemini já retornou valor fora do intervalo
        # (causou "numeric field overflow" no Postgres, coluna NUMERIC(4,3)). Nunca
        # confiar em faixa de saída de LLM sem validar/normalizar no boundary.
        aderencia_script=max(0.0, min(1.0, float(parsed.get("aderencia_script", 0.0)))),
        criticidade=parsed.get("criticidade", "baixa"),
        findings=findings,
        insights_json=parsed,
        usage=usage,
        model_id=model_id,
    )
