"""
stt_diarize.py — Transcrição + diarização por locutor + tom semântico em UMA
única chamada ao Gemini.

Achado da Fase 0: o áudio é mono (sem canal separado agente/cliente), então a
diarização não pode vir de split de canal — é pedida diretamente ao modelo,
que tem acesso ao áudio completo e ao contexto da chamada (nome do atendente,
direção) para inferir quem fala em cada turno. É uma técnica aproximada,
sujeita a ajuste conforme o volume real de chamadas processadas mostrar erros
sistemáticos de atribuição de locutor.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

from google.genai import types as t

from src.audio_decode import pcm_to_wav
from src.gemini_client import get_client
from src.token_usage import TokenUsage, extract_usage

logger = logging.getLogger("asteriskia.insights.stt_diarize")

_TONE_ENUM = ["calmo", "neutro", "tenso", "irritado", "empolgado"]
_SPEAKER_ENUM = ["agente", "cliente", "indefinido"]

_RESPONSE_SCHEMA = t.Schema(
    type=t.Type.OBJECT,
    properties={
        "segments": t.Schema(
            type=t.Type.ARRAY,
            items=t.Schema(
                type=t.Type.OBJECT,
                properties={
                    "speaker": t.Schema(type=t.Type.STRING, enum=_SPEAKER_ENUM),
                    "start_ms": t.Schema(type=t.Type.INTEGER),
                    "end_ms": t.Schema(type=t.Type.INTEGER),
                    "text": t.Schema(type=t.Type.STRING),
                    "tone_semantic": t.Schema(type=t.Type.STRING, enum=_TONE_ENUM),
                },
                required=["speaker", "start_ms", "end_ms", "text", "tone_semantic"],
            ),
        ),
    },
    required=["segments"],
)


@dataclass(frozen=True)
class TranscriptSegment:
    speaker: str
    start_ms: int
    end_ms: int
    text: str
    tone_semantic: str


@dataclass(frozen=True)
class DiarizationResult:
    segments: list[TranscriptSegment]
    usage: TokenUsage | None
    model_id: str


def _build_prompt(agent_name: str | None, direction: str | None) -> str:
    contexto = []
    if agent_name:
        contexto.append(f"O nome do atendente nesta chamada é \"{agent_name}\".")
    if direction == "inbound":
        contexto.append("Esta é uma chamada recebida (o cliente ligou para o atendente).")
    elif direction == "outbound":
        contexto.append("Esta é uma chamada efetuada (o atendente ligou para o cliente).")
    contexto_str = " ".join(contexto)

    return (
        "Transcreva integralmente esta ligação telefônica em português do Brasil, "
        "dividindo em turnos de fala (segmentos). Para cada turno, identifique quem "
        "fala — \"agente\" (o atendente/funcionário) ou \"cliente\" (a pessoa que "
        "liga ou é chamada) — e use \"indefinido\" apenas se genuinamente não for "
        "possível distinguir. " + contexto_str + " "
        "Para cada turno, classifique também o tom de voz percebido em uma destas "
        "categorias: calmo, neutro, tenso, irritado, empolgado. "
        "Retorne start_ms e end_ms relativos ao início do áudio (em milissegundos)."
    )


def _transcribe_sync(pcm: bytes, model_id: str, agent_name: str | None, direction: str | None) -> tuple[str, object]:
    wav_bytes = pcm_to_wav(pcm)
    prompt = _build_prompt(agent_name, direction)

    resp = get_client().models.generate_content(
        model=model_id,
        contents=[t.Content(parts=[
            t.Part(text=prompt),
            t.Part(inline_data=t.Blob(mime_type="audio/wav", data=wav_bytes)),
        ])],
        config=t.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=_RESPONSE_SCHEMA,
        ),
    )
    return resp.text or "{}", resp


async def transcribe_and_diarize(
    pcm: bytes,
    model_id: str,
    agent_name: str | None,
    direction: str | None,
) -> DiarizationResult:
    import json

    raw_text, resp = await asyncio.to_thread(_transcribe_sync, pcm, model_id, agent_name, direction)
    usage = extract_usage(resp)

    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError as e:
        logger.error("Resposta do Gemini não é JSON válido para diarização: %s", e)
        parsed = {"segments": []}

    segments = [
        TranscriptSegment(
            speaker=seg.get("speaker", "indefinido"),
            start_ms=int(seg.get("start_ms", 0)),
            end_ms=int(seg.get("end_ms", 0)),
            text=seg.get("text", ""),
            tone_semantic=seg.get("tone_semantic", "neutro"),
        )
        for seg in parsed.get("segments", [])
    ]

    return DiarizationResult(segments=segments, usage=usage, model_id=model_id)
