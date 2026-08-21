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
import dataclasses
import logging
from datetime import datetime

import uvicorn

from src.audio_decode import PCM_SAMPLE_RATE, decode_to_pcm
from src.backend_client import (
    get_active_scorecard,
    get_known_call_refs,
    get_pending_callcenter_recordings,
    get_pending_reports,
    get_pending_uploads,
    mark_error,
    mark_processing,
    mark_report_error,
    mark_report_processing,
    register_pending,
    submit_insights,
    submit_report_result,
)
from src.config import (
    AUDIO_DIR,
    MAX_CONCURRENT_PROCESSING,
    POLL_INTERVAL_SECONDS,
    get_gemini_model_insights,
    get_gemini_model_stt,
)
from src.discovery import AudioPair, discover_pairs
from src.embedding_server import app as embedding_app
from src.evaluation_llm import EvaluationResult, ScorecardItemInput, evaluate_call
from src.insights_llm import generate_insights
from src.masking import mask_segments
from src.prosody import compute_acoustic_tones
from src.report_narrative_llm import generate_narrative
from src.stt_diarize import transcribe_and_diarize
from src.xml_parser import CallMetadata, XmlParseError, parse_call_xml

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("asteriskia.insights.main")


def _build_payload(metadata: CallMetadata, pair: AudioPair, diarization, tones: list[str], insights,
                    scorecard: dict | None, evaluation: EvaluationResult | None) -> dict:
    segments_payload = [
        {
            "speaker": seg.speaker,
            "startMs": seg.start_ms,
            "endMs": seg.end_ms,
            "text": seg.text,  # já mascarado em mask_segments (ver acima)
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
        "agentLoginId": metadata.agent_login_id,
        "extension": metadata.extension,
        "ani": metadata.ani,
        "dnis": metadata.dnis,
        "direction": metadata.direction,
        "skill": metadata.skill,
        "xmlRaw": metadata.xml_raw,
        "customerNumber": metadata.customer_number,
        "organization": metadata.organization,
        "disconnectedBy": metadata.disconnected_by,
        "numberOfHolds": metadata.number_of_holds,
        "totalHoldTime": metadata.total_hold_time,
        "numberOfTransfers": metadata.number_of_transfers,
        "numberOfConferences": metadata.number_of_conferences,
        "wrapupTime": metadata.wrapup_time,
        "codec": metadata.codec,
        "missedRtpPackets": metadata.missed_rtp_packets,
        "decodingErrors": metadata.decoding_errors,
        "switchCallId": metadata.switch_call_id,
        "trunk": metadata.trunk,
        "captureType": metadata.capture_type,
        "datasourceName": metadata.datasource_name,
        "transferEvents": [
            {
                "transferredAt": event.transferred_at.isoformat() if event.transferred_at else None,
                "disconnectedBy": event.disconnected_by,
                "targetSwitchCallId": event.target_switch_call_id,
            }
            for event in metadata.transfer_events
        ],
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
        "evaluation": _build_evaluation_payload(scorecard, evaluation),
    }


def _build_evaluation_payload(scorecard: dict | None, evaluation: EvaluationResult | None) -> dict | None:
    if scorecard is None or evaluation is None:
        return None
    return {
        "scorecardId": scorecard["id"],
        "items": [
            {
                "itemId": item.item_id,
                "nota": item.nota,
                "justificativa": item.justificativa,
                "trechoReferencia": item.trecho_referencia,
            }
            for item in evaluation.items
        ],
        "llmTokensIn": evaluation.usage.input_tokens if evaluation.usage else 0,
        "llmTokensOut": evaluation.usage.output_tokens if evaluation.usage else 0,
        "llmModel": evaluation.model_id,
    }


async def _safe_mark_error(call_ref: str, error_msg: str) -> None:
    """mark_error nunca deve derrubar o pipeline — se o próprio backend estiver
    inacessível nesse instante, loga e segue; a chamada só fica invisível na aba
    Processamento até o próximo ciclo conseguir persistir o erro."""
    try:
        await mark_error(call_ref, error_msg)
    except Exception as e:
        logger.warning("call_ref=%s: falha ao registrar erro no backend — %s", call_ref, e)


async def _evaluate_against_active_scorecard(
    call_ref: str, segments
) -> tuple[dict | None, EvaluationResult | None]:
    """Avalia a chamada contra a ficha ativa, se houver — retrocompatível: sem ficha
    ativa (ou falha ao consultá-la), a avaliação é pulada e o pipeline segue igual ao
    comportamento anterior a esta feature (Fase 1 do Quality Management, V38)."""
    try:
        scorecard = await get_active_scorecard()
    except Exception as e:
        logger.warning("call_ref=%s: falha ao consultar ficha ativa — seguindo sem avaliação: %s", call_ref, e)
        return None, None

    if scorecard is None:
        return None, None

    items = [
        ScorecardItemInput(
            item_id=item["id"],
            pergunta=item["pergunta"],
            nota_maxima=item["notaMaxima"],
            is_critical=item["isCritical"],
        )
        for item in scorecard.get("items", [])
    ]
    if not items:
        return None, None

    try:
        evaluation = await evaluate_call(segments, items, get_gemini_model_insights())
    except Exception as e:
        logger.error("call_ref=%s: falha na avaliação por IA — seguindo sem avaliação: %s", call_ref, e)
        return None, None

    return scorecard, evaluation


async def process_pair(pair: AudioPair) -> None:
    logger.info("Processando call_ref=%s", pair.call_ref)
    try:
        await mark_processing(pair.call_ref, pair.wav_path, pair.xml_path)
    except Exception as e:
        logger.warning("call_ref=%s: falha ao marcar início de processamento — %s", pair.call_ref, e)

    try:
        # Descarregado para thread — diferente de decode_to_pcm (já em to_thread),
        # o parse de XML rodava direto no loop compartilhado com todas as
        # chamadas/ingestões simultâneas deste processo.
        metadata = await asyncio.to_thread(parse_call_xml, pair.xml_path)
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
        # Mascara ANTES do LLM ver o texto — sem isso, um achado que cita um trecho literal
        # da transcrição (trecho_referencia) ou o resumo podiam ecoar CPF/cartão/telefone
        # mesmo com o segments_payload final mascarado.
        diarization = dataclasses.replace(diarization, segments=mask_segments(diarization.segments))
        tones = await asyncio.to_thread(compute_acoustic_tones, pcm, diarization.segments)
        insights = await generate_insights(
            diarization.segments, metadata.skill, metadata.duration_seconds, get_gemini_model_insights()
        )
    except Exception as e:
        logger.error("call_ref=%s: falha na análise de IA — %s", pair.call_ref, e)
        await _safe_mark_error(pair.call_ref, f"Falha na análise de IA: {e}")
        return

    scorecard, evaluation = await _evaluate_against_active_scorecard(pair.call_ref, diarization.segments)

    payload = _build_payload(metadata, pair, diarization, tones, insights, scorecard, evaluation)

    try:
        await submit_insights(payload)
    except Exception as e:
        logger.error("call_ref=%s: falha ao enviar resultado ao backend — %s", pair.call_ref, e)
        await _safe_mark_error(pair.call_ref, f"Falha ao enviar resultado ao backend: {e}")
        return

    logger.info("call_ref=%s processado com sucesso (%d segmentos, criticidade=%s)",
                pair.call_ref, len(diarization.segments), insights.criticidade)


async def _process_pending_report(report: dict) -> None:
    """Gera a narrativa de um relatório de performance pendente (Fase 2 do Quality
    Management, V39) — o agregado numérico já vem pronto do Java; este processo só
    chama o LLM para escrever o texto e devolve o resultado."""
    report_id = report["id"]
    logger.info("Gerando narrativa do relatório de performance id=%s (agente=%s)", report_id, report["agentName"])
    try:
        await mark_report_processing(report_id)
    except Exception as e:
        logger.warning("report_id=%s: falha ao marcar início de processamento — %s", report_id, e)

    try:
        aggregate = report["content"]["aggregate"]
        evolution = report.get("evolution")
        narrative = await generate_narrative(
            report["agentName"], report["dateFrom"], report["dateTo"],
            aggregate, evolution, get_gemini_model_insights(),
        )
    except Exception as e:
        logger.error("report_id=%s: falha ao gerar narrativa — %s", report_id, e)
        try:
            await mark_report_error(report_id, f"Falha ao gerar narrativa: {e}")
        except Exception as inner:
            logger.warning("report_id=%s: falha ao registrar erro no backend — %s", report_id, inner)
        return

    payload = {
        "pontosFortes": narrative.pontos_fortes,
        "pontosMelhoria": narrative.pontos_melhoria,
        "recomendacoes": narrative.recomendacoes,
        "comparacaoTextual": narrative.comparacao_textual,
        "llmTokensIn": narrative.usage.input_tokens if narrative.usage else 0,
        "llmTokensOut": narrative.usage.output_tokens if narrative.usage else 0,
        "llmModel": narrative.model_id,
    }
    try:
        await submit_report_result(report_id, payload)
    except Exception as e:
        logger.error("report_id=%s: falha ao enviar narrativa ao backend — %s", report_id, e)
        return

    logger.info("Relatório de performance id=%s concluído", report_id)


async def _poll_reports_once() -> None:
    try:
        pending = await get_pending_reports()
    except Exception as e:
        logger.warning("Não foi possível consultar relatórios pendentes — pulando ciclo: %s", e)
        return

    if not pending:
        return

    logger.info("%d relatório(s) de performance pendente(s) de narrativa", len(pending))
    await asyncio.gather(*(_process_pending_report(r) for r in pending), return_exceptions=True)


async def process_upload_item(item: dict) -> None:
    """Processa um áudio do portal do supervisor (Fase 3 do Quality Management, V40) —
    mesmo pipeline de STT/análise/avaliação do fluxo Verint, mas sem XML: metadados
    (atendente, direção) já vieram do registro feito no upload; duração é derivada do
    PCM decodificado em vez de lida de metadado externo."""
    call_ref = item["callRef"]
    wav_path = item["wavPath"]
    agent_name = item.get("agentName")
    direction = item.get("direction")

    logger.info("Processando upload call_ref=%s", call_ref)
    try:
        await mark_processing(call_ref, wav_path, None)
    except Exception as e:
        logger.warning("call_ref=%s: falha ao marcar início de processamento — %s", call_ref, e)

    try:
        pcm = await decode_to_pcm(wav_path)
    except Exception as e:
        logger.error("call_ref=%s: falha ao decodificar áudio de upload — %s", call_ref, e)
        await _safe_mark_error(call_ref, f"Falha ao decodificar áudio: {e}")
        return

    duration_seconds = len(pcm) // (PCM_SAMPLE_RATE * 2)

    try:
        diarization = await transcribe_and_diarize(pcm, get_gemini_model_stt(), agent_name, direction)
        diarization = dataclasses.replace(diarization, segments=mask_segments(diarization.segments))
        tones = await asyncio.to_thread(compute_acoustic_tones, pcm, diarization.segments)
        insights = await generate_insights(diarization.segments, None, duration_seconds, get_gemini_model_insights())
    except Exception as e:
        logger.error("call_ref=%s: falha na análise de IA (upload) — %s", call_ref, e)
        await _safe_mark_error(call_ref, f"Falha na análise de IA: {e}")
        return

    scorecard, evaluation = await _evaluate_against_active_scorecard(call_ref, diarization.segments)

    segments_payload = [
        {
            "speaker": seg.speaker, "startMs": seg.start_ms, "endMs": seg.end_ms,
            "text": seg.text, "toneSemantic": seg.tone_semantic, "toneAcoustic": tone,  # já mascarado em mask_segments
        }
        for seg, tone in zip(diarization.segments, tones)
    ]
    findings_payload = [
        {"tipo": f.tipo, "descricao": f.descricao, "trechoReferencia": f.trecho_referencia, "prioridade": f.prioridade}
        for f in insights.findings
    ]
    payload = {
        "callRef": call_ref,
        "wavPath": wav_path,
        "xmlPath": None,
        "durationSeconds": duration_seconds,
        "callStarttime": None,
        "agentName": agent_name,
        "direction": direction,
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
        "evaluation": _build_evaluation_payload(scorecard, evaluation),
    }

    try:
        await submit_insights(payload)
    except Exception as e:
        logger.error("call_ref=%s: falha ao enviar resultado de upload ao backend — %s", call_ref, e)
        await _safe_mark_error(call_ref, f"Falha ao enviar resultado ao backend: {e}")
        return

    logger.info("call_ref=%s (upload) processado com sucesso (%d segmentos, criticidade=%s)",
                call_ref, len(diarization.segments), insights.criticidade)


async def process_callcenter_item(item: dict) -> None:
    """Processa uma gravação do Call Center (Fase 8) — mesmo pipeline de STT/análise/
    avaliação do fluxo de upload (sem XML), mas com skill (fila) e ani já resolvidos pelo
    Java a partir de cc_interactions no momento do registro (ver
    CallCenterRecordingService.registerInsights)."""
    call_ref = item["callRef"]
    wav_path = item["wavPath"]
    agent_name = item.get("agentName")
    direction = item.get("direction") or "inbound"
    skill = item.get("skill")
    ani = item.get("ani")

    logger.info("Processando gravação do Call Center call_ref=%s", call_ref)
    try:
        await mark_processing(call_ref, wav_path, None)
    except Exception as e:
        logger.warning("call_ref=%s: falha ao marcar início de processamento — %s", call_ref, e)

    try:
        pcm = await decode_to_pcm(wav_path)
    except Exception as e:
        logger.error("call_ref=%s: falha ao decodificar áudio do Call Center — %s", call_ref, e)
        await _safe_mark_error(call_ref, f"Falha ao decodificar áudio: {e}")
        return

    duration_seconds = len(pcm) // (PCM_SAMPLE_RATE * 2)

    try:
        diarization = await transcribe_and_diarize(pcm, get_gemini_model_stt(), agent_name, direction)
        diarization = dataclasses.replace(diarization, segments=mask_segments(diarization.segments))
        tones = await asyncio.to_thread(compute_acoustic_tones, pcm, diarization.segments)
        insights = await generate_insights(diarization.segments, skill, duration_seconds, get_gemini_model_insights())
    except Exception as e:
        logger.error("call_ref=%s: falha na análise de IA (Call Center) — %s", call_ref, e)
        await _safe_mark_error(call_ref, f"Falha na análise de IA: {e}")
        return

    scorecard, evaluation = await _evaluate_against_active_scorecard(call_ref, diarization.segments)

    segments_payload = [
        {
            "speaker": seg.speaker, "startMs": seg.start_ms, "endMs": seg.end_ms,
            "text": seg.text, "toneSemantic": seg.tone_semantic, "toneAcoustic": tone,  # já mascarado em mask_segments
        }
        for seg, tone in zip(diarization.segments, tones)
    ]
    findings_payload = [
        {"tipo": f.tipo, "descricao": f.descricao, "trechoReferencia": f.trecho_referencia, "prioridade": f.prioridade}
        for f in insights.findings
    ]
    payload = {
        "callRef": call_ref,
        "wavPath": wav_path,
        "xmlPath": None,
        "durationSeconds": duration_seconds,
        "callStarttime": None,
        "agentName": agent_name,
        "ani": ani,
        "direction": direction,
        "skill": skill,
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
        "evaluation": _build_evaluation_payload(scorecard, evaluation),
    }

    try:
        await submit_insights(payload)
    except Exception as e:
        logger.error("call_ref=%s: falha ao enviar resultado do Call Center ao backend — %s", call_ref, e)
        await _safe_mark_error(call_ref, f"Falha ao enviar resultado ao backend: {e}")
        return

    logger.info("call_ref=%s (Call Center) processado com sucesso (%d segmentos, criticidade=%s)",
                call_ref, len(diarization.segments), insights.criticidade)


async def _poll_callcenter_once(semaphore: asyncio.Semaphore) -> None:
    try:
        pending = await get_pending_callcenter_recordings()
    except Exception as e:
        logger.warning("Não foi possível consultar gravações do Call Center pendentes — pulando ciclo: %s", e)
        return

    if not pending:
        return

    logger.info("%d gravação(ões) do Call Center pendente(s)", len(pending))

    async def _bounded(item: dict) -> None:
        async with semaphore:
            await process_callcenter_item(item)

    await asyncio.gather(*(_bounded(item) for item in pending), return_exceptions=True)


async def _poll_uploads_once(semaphore: asyncio.Semaphore) -> None:
    try:
        pending = await get_pending_uploads()
    except Exception as e:
        logger.warning("Não foi possível consultar uploads pendentes — pulando ciclo: %s", e)
        return

    if not pending:
        return

    logger.info("%d upload(s) pendente(s) do portal do supervisor", len(pending))

    async def _bounded(item: dict) -> None:
        async with semaphore:
            await process_upload_item(item)

    await asyncio.gather(*(_bounded(item) for item in pending), return_exceptions=True)


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
        try:
            await _poll_uploads_once(semaphore)
        except Exception as e:
            logger.exception("Erro inesperado no ciclo de uploads do portal do supervisor: %s", e)
        try:
            await _poll_callcenter_once(semaphore)
        except Exception as e:
            logger.exception("Erro inesperado no ciclo de gravações do Call Center: %s", e)
        try:
            await _poll_reports_once()
        except Exception as e:
            logger.exception("Erro inesperado no ciclo de relatórios de performance: %s", e)
        elapsed = (datetime.now() - started).total_seconds()
        await asyncio.sleep(max(0.0, POLL_INTERVAL_SECONDS - elapsed))


async def run_embedding_server() -> None:
    """Sobe o servidor HTTP interno de embeddings (Fase 25 — base de
    conhecimento/RAG do chat do Call Center) na porta 8000 — sem publicação
    ao host, só acessível pela rede interna docker."""
    config = uvicorn.Config(embedding_app, host="0.0.0.0", port=8000, log_level="warning")
    server = uvicorn.Server(config)
    await server.serve()


async def main() -> None:
    # Roda o loop de polling (voz — Verint/portal do supervisor/Call Center)
    # concorrentemente com o servidor HTTP de embeddings — mesmo processo,
    # mesmo container, duas responsabilidades assíncronas independentes.
    await asyncio.gather(poll_loop(), run_embedding_server())


if __name__ == "__main__":
    asyncio.run(main())
