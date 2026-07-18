"""
main.py — Loop de polling do serviço de Insights.

Descobre pares .wav+.xml novos em /opt/audio (ainda não conhecidos pelo
backend), decodifica o áudio, transcreve+diarize, calcula tom acústico,
gera os insights estruturados e envia tudo ao backend Java — que persiste
(Fase 3 deste plano) e expõe a busca/dashboard da tela Insights.

Nunca acessa o Postgres direto (mesmo padrão do ai-agent): toda consulta de
estado e toda escrita passam pelo backend via backend_client.py.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime

from src.audio_decode import decode_to_pcm
from src.backend_client import (
    get_known_call_refs,
    mark_error,
    mark_processing,
    register_pending,
    submit_insights,
)
from src.config import (
    AUDIO_DIR,
    MAX_CONCURRENT_PROCESSING,
    POLL_INTERVAL_SECONDS,
    get_gemini_model_insights,
    get_gemini_model_stt,
)
from src.discovery import AudioPair, discover_pairs
from src.insights_llm import generate_insights
from src.prosody import compute_acoustic_tones
from src.stt_diarize import transcribe_and_diarize
from src.xml_parser import CallMetadata, XmlParseError, parse_call_xml

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("asteriskia.insights.main")


def _build_payload(metadata: CallMetadata, pair: AudioPair, diarization, tones: list[str], insights) -> dict:
    segments_payload = [
        {
            "speaker": seg.speaker,
            "startMs": seg.start_ms,
            "endMs": seg.end_ms,
            "text": seg.text,
            "toneSemantic": seg.tone_semantic,
            "toneAcoustic": tone,
        }
        for seg, tone in zip(diarization.segments, tones)
    ]

    findings_payload = [
        {
            "tipo": f.tipo,
            "descricao": f.descricao,
            "trechoReferencia": f.trecho_referencia,
            "prioridade": f.prioridade,
        }
        for f in insights.findings
    ]

    return {
        "callRef": metadata.call_ref,
        "wavPath": pair.wav_path,
        "xmlPath": pair.xml_path,
        "durationSeconds": metadata.duration_seconds,
        "callStarttime": metadata.call_starttime.isoformat() if metadata.call_starttime else None,
        "agentName": metadata.agent_name,
        "agentIdVerint": metadata.agent_id_verint,
        "extension": metadata.extension,
        "ani": metadata.ani,
        "dnis": metadata.dnis,
        "direction": metadata.direction,
        "skill": metadata.skill,
        "xmlRaw": metadata.xml_raw,
        "sttTokensIn": diarization.usage.input_tokens if diarization.usage else 0,
        "sttTokensOut": diarization.usage.output_tokens if diarization.usage else 0,
        "sttModel": diarization.model_id,
        "llmTokensIn": insights.usage.input_tokens if insights.usage else 0,
        "llmTokensOut": insights.usage.output_tokens if insights.usage else 0,
        "llmModel": insights.model_id,
        "segments": segments_payload,
        "insights": {
            "resumo": insights.resumo,
            "categoriaAssunto": insights.categoria_assunto,
            "sentimentoGeral": insights.sentimento_geral,
            "aderenciaScript": insights.aderencia_script,
            "criticidade": insights.criticidade,
            "insightsJson": insights.insights_json,
        },
        "findings": findings_payload,
    }


async def _safe_mark_error(call_ref: str, error_msg: str) -> None:
    """mark_error nunca deve derrubar o pipeline — se o próprio backend estiver
    inacessível nesse instante, loga e segue; a chamada só fica invisível na aba
    Processamento até o próximo ciclo conseguir persistir o erro."""
    try:
        await mark_error(call_ref, error_msg)
    except Exception as e:
        logger.warning("call_ref=%s: falha ao registrar erro no backend — %s", call_ref, e)


async def process_pair(pair: AudioPair) -> None:
    logger.info("Processando call_ref=%s", pair.call_ref)
    try:
        await mark_processing(pair.call_ref, pair.wav_path, pair.xml_path)
    except Exception as e:
        logger.warning("call_ref=%s: falha ao marcar início de processamento — %s", pair.call_ref, e)

    try:
        metadata = parse_call_xml(pair.xml_path)
    except XmlParseError as e:
        logger.error("call_ref=%s: falha ao parsear XML — %s", pair.call_ref, e)
        await _safe_mark_error(pair.call_ref, f"Falha ao parsear XML: {e}")
        return

    try:
        pcm = await decode_to_pcm(pair.wav_path)
    except Exception as e:
        logger.error("call_ref=%s: falha ao decodificar áudio — %s", pair.call_ref, e)
        await _safe_mark_error(pair.call_ref, f"Falha ao decodificar áudio: {e}")
        return

    try:
        diarization = await transcribe_and_diarize(
            pcm, get_gemini_model_stt(), metadata.agent_name, metadata.direction
        )
        tones = await asyncio.to_thread(compute_acoustic_tones, pcm, diarization.segments)
        insights = await generate_insights(
            diarization.segments, metadata.skill, metadata.duration_seconds, get_gemini_model_insights()
        )
    except Exception as e:
        logger.error("call_ref=%s: falha na análise de IA — %s", pair.call_ref, e)
        await _safe_mark_error(pair.call_ref, f"Falha na análise de IA: {e}")
        return

    payload = _build_payload(metadata, pair, diarization, tones, insights)

    try:
        await submit_insights(payload)
    except Exception as e:
        logger.error("call_ref=%s: falha ao enviar resultado ao backend — %s", pair.call_ref, e)
        await _safe_mark_error(pair.call_ref, f"Falha ao enviar resultado ao backend: {e}")
        return

    logger.info("call_ref=%s processado com sucesso (%d segmentos, criticidade=%s)",
                pair.call_ref, len(diarization.segments), insights.criticidade)


async def _poll_once(semaphore: asyncio.Semaphore) -> None:
    try:
        known_refs = await get_known_call_refs()
    except Exception as e:
        # Se o backend estiver inacessível, não processa nada neste ciclo —
        # tratar como "sem novidade" arriscaria reprocessar (e recobrar da API
        # Gemini) tudo que já foi enviado anteriormente.
        logger.warning("Não foi possível consultar call_refs conhecidos no backend — pulando ciclo: %s", e)
        return

    pairs = discover_pairs(AUDIO_DIR)
    # 'done' -> já processado com sucesso, pula. Qualquer outro caso (nunca visto,
    # 'pending', 'processing' de um ciclo anterior que não terminou, ou 'error') entra
    # na fila de novo — mesmo comportamento de retry de sempre, agora com status visível
    # na aba Processamento em vez de só nos logs do container.
    to_process = [p for p in pairs if known_refs.get(p.call_ref) != "done"]

    if not to_process:
        logger.debug("Nenhum par pendente em %s (%d pares descobertos, todos concluídos)", AUDIO_DIR, len(pairs))
        return

    novos = [p for p in to_process if p.call_ref not in known_refs]
    for pair in novos:
        try:
            await register_pending(pair.call_ref, pair.wav_path, pair.xml_path)
        except Exception as e:
            logger.warning("call_ref=%s: falha ao registrar como pendente — %s", pair.call_ref, e)

    logger.info("%d par(es) a processar (%d novo(s))", len(to_process), len(novos))

    async def _bounded(pair: AudioPair) -> None:
        async with semaphore:
            await process_pair(pair)

    await asyncio.gather(*(_bounded(p) for p in to_process), return_exceptions=True)


async def poll_loop() -> None:
    logger.info(
        "Serviço de Insights iniciado — AUDIO_DIR=%s, intervalo=%ds, concorrência máx=%d",
        AUDIO_DIR, POLL_INTERVAL_SECONDS, MAX_CONCURRENT_PROCESSING,
    )
    semaphore = asyncio.Semaphore(MAX_CONCURRENT_PROCESSING)
    while True:
        started = datetime.now()
        try:
            await _poll_once(semaphore)
        except Exception as e:
            logger.exception("Erro inesperado no ciclo de polling: %s", e)
        elapsed = (datetime.now() - started).total_seconds()
        await asyncio.sleep(max(0.0, POLL_INTERVAL_SECONDS - elapsed))


if __name__ == "__main__":
    asyncio.run(poll_loop())
