package com.asteriskia.domain.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * InsightsIngestionService — persiste o resultado de processamento enviado
 * pelo serviço asteriskia-insights (POST /api/v1/internal/insights).
 *
 * Upsert por callRef: se a chamada já existe (reprocessamento), os segmentos/
 * insight/achados anteriores são substituídos por completo — mais simples e
 * mais seguro do que tentar reconciliar diffs, e o serviço Python só reenvia
 * uma chamada quando reprocessa de propósito (nunca em paralelo consigo mesma).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsIngestionService {

    private final CallAudioFileRepository audioFileRepository;
    private final CallTranscriptSegmentRepository segmentRepository;
    private final CallInsightRepository insightRepository;
    private final CallInsightFindingRepository findingRepository;
    private final CallTransferEventRepository transferEventRepository;
    private final TransferResolutionService transferResolutionService;
    private final EvaluationService evaluationService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CallAudioFile ingest(IngestInsightsRequest request) {
        CallAudioFile audioFile = audioFileRepository.findByCallRef(request.callRef())
                .orElseGet(() -> CallAudioFile.builder().callRef(request.callRef()).build());

        audioFile.setWavPath(request.wavPath());
        audioFile.setXmlPath(request.xmlPath());
        audioFile.setDurationSeconds(request.durationSeconds());
        audioFile.setCallStarttime(request.callStarttime() != null ? request.callStarttime().toLocalDateTime() : null);
        audioFile.setAgentName(request.agentName());
        audioFile.setAgentIdVerint(request.agentIdVerint());
        audioFile.setExtension(request.extension());
        audioFile.setAni(request.ani());
        audioFile.setDnis(request.dnis());
        audioFile.setDirection(request.direction());
        audioFile.setSkill(request.skill());
        audioFile.setXmlRaw(toJsonString(request.xmlRaw()));
        applyMetadataFields(audioFile, request.agentLoginId(), request.customerNumber(), request.organization(),
                request.disconnectedBy(), request.numberOfHolds(), request.totalHoldTime(),
                request.numberOfTransfers(), request.numberOfConferences(), request.wrapupTime(), request.codec(),
                request.missedRtpPackets(), request.decodingErrors(), request.switchCallId(), request.trunk(),
                request.captureType(), request.datasourceName());
        audioFile.setStatus("done");
        audioFile.setErrorMsg(null);
        audioFile.setProcessedAt(LocalDateTime.now());
        audioFile.setSttTokensIn(request.sttTokensIn());
        audioFile.setSttTokensOut(request.sttTokensOut());
        audioFile.setSttModel(request.sttModel());
        audioFile.setLlmTokensIn(request.llmTokensIn());
        audioFile.setLlmTokensOut(request.llmTokensOut());
        audioFile.setLlmModel(request.llmModel());
        audioFile = audioFileRepository.save(audioFile);

        Long audioFileId = audioFile.getId();
        segmentRepository.deleteByAudioFileId(audioFileId);
        insightRepository.deleteByAudioFileId(audioFileId);
        findingRepository.deleteByAudioFileId(audioFileId);

        List<CallTranscriptSegment> segments = request.segments().stream()
                .map(s -> CallTranscriptSegment.builder()
                        .audioFileId(audioFileId)
                        .speaker(s.speaker())
                        .startMs(s.startMs())
                        .endMs(s.endMs())
                        .text(s.text())
                        .toneSemantic(s.toneSemantic())
                        .toneAcoustic(s.toneAcoustic())
                        .build())
                .toList();
        segmentRepository.saveAll(segments);

        IngestInsightsRequest.InsightsPayload insightsPayload = request.insights();
        insightRepository.save(CallInsight.builder()
                .audioFileId(audioFileId)
                .resumo(insightsPayload.resumo())
                .categoriaAssunto(insightsPayload.categoriaAssunto())
                .sentimentoGeral(insightsPayload.sentimentoGeral())
                .aderenciaScript(clampAderenciaScript(insightsPayload.aderenciaScript()))
                .criticidade(insightsPayload.criticidade())
                .insightsJson(toJsonString(insightsPayload.insightsJson()))
                .build());

        if (request.findings() != null) {
            List<CallInsightFinding> findings = request.findings().stream()
                    .map(f -> CallInsightFinding.builder()
                            .audioFileId(audioFileId)
                            .tipo(f.tipo())
                            .descricao(f.descricao())
                            .trechoReferencia(f.trechoReferencia())
                            .prioridade(f.prioridade() != null ? f.prioridade() : "media")
                            .build())
                    .toList();
            findingRepository.saveAll(findings);
        }

        if (request.evaluation() != null) {
            IngestInsightsRequest.EvaluationPayload evaluation = request.evaluation();
            List<EvaluationService.EvaluatedItem> evaluatedItems = evaluation.items().stream()
                    .map(i -> new EvaluationService.EvaluatedItem(i.itemId(), i.nota(), i.justificativa(), i.trechoReferencia()))
                    .toList();
            evaluationService.evaluate(audioFileId, evaluation.scorecardId(), evaluatedItems,
                    evaluation.llmTokensIn(), evaluation.llmTokensOut(), evaluation.llmModel());
        }

        replaceTransferEvents(audioFileId, request.transferEvents());
        transferResolutionService.resolveForAudioFile(audioFile);

        log.info("Insights persistidos para call_ref={} (id={}, {} segmentos, criticidade={})",
                request.callRef(), audioFileId, segments.size(), insightsPayload.criticidade());

        return audioFile;
    }

    /** Backfill metadata-only (insights/src/backfill_metadata.py) — atualiza só os campos
     * novos do grupo A/B/C/D (V43) de uma chamada JÁ processada, sem tocar status/
     * transcrição/insights/avaliação. Nunca reprocessa STT/LLM. */
    @Transactional
    public void updateMetadata(String callRef, InsightsMetadataUpdateRequest request) {
        CallAudioFile audioFile = audioFileRepository.findByCallRef(callRef)
                .orElseThrow(() -> new IllegalArgumentException("Chamada não encontrada para backfill: callRef=" + callRef));

        applyMetadataFields(audioFile, request.agentLoginId(), request.customerNumber(), request.organization(),
                request.disconnectedBy(), request.numberOfHolds(), request.totalHoldTime(),
                request.numberOfTransfers(), request.numberOfConferences(), request.wrapupTime(), request.codec(),
                request.missedRtpPackets(), request.decodingErrors(), request.switchCallId(), request.trunk(),
                request.captureType(), request.datasourceName());
        audioFile = audioFileRepository.save(audioFile);

        replaceTransferEvents(audioFile.getId(), request.transferEvents());
        transferResolutionService.resolveForAudioFile(audioFile);

        log.info("Metadados (backfill) atualizados para call_ref={} (id={})", callRef, audioFile.getId());
    }

    private void applyMetadataFields(CallAudioFile audioFile, String agentLoginId, String customerNumber,
            String organization, String disconnectedBy, Integer numberOfHolds, Integer totalHoldTime,
            Integer numberOfTransfers, Integer numberOfConferences, Integer wrapupTime, String codec,
            Integer missedRtpPackets, Integer decodingErrors, String switchCallId, String trunk, String captureType,
            String datasourceName) {
        audioFile.setAgentLoginId(agentLoginId);
        audioFile.setCustomerNumber(customerNumber);
        audioFile.setOrganization(organization);
        audioFile.setDisconnectedBy(disconnectedBy);
        audioFile.setNumberOfHolds(numberOfHolds);
        audioFile.setTotalHoldTime(totalHoldTime);
        audioFile.setNumberOfTransfers(numberOfTransfers);
        audioFile.setNumberOfConferences(numberOfConferences);
        audioFile.setWrapupTime(wrapupTime);
        audioFile.setCodec(codec);
        audioFile.setMissedRtpPackets(missedRtpPackets);
        audioFile.setDecodingErrors(decodingErrors);
        audioFile.setSwitchCallId(switchCallId);
        audioFile.setTrunk(trunk);
        audioFile.setCaptureType(captureType);
        audioFile.setDatasourceName(datasourceName);
    }

    /** Substitui por completo os eventos de transferência da chamada — mesmo padrão
     * upsert-by-delete-and-recreate usado pra segmentos/achados. Eventos já resolvidos
     * perdem a resolução e são re-resolvidos do zero logo em seguida (idempotente: a
     * correlação depende só de dados já persistidos, não de estado acumulado). */
    private void replaceTransferEvents(Long audioFileId, List<IngestInsightsRequest.TransferEventPayload> events) {
        transferEventRepository.deleteByAudioFileId(audioFileId);
        if (events == null || events.isEmpty()) {
            return;
        }
        short order = 1;
        List<CallTransferEvent> entities = new java.util.ArrayList<>();
        for (IngestInsightsRequest.TransferEventPayload event : events) {
            entities.add(CallTransferEvent.builder()
                    .audioFileId(audioFileId)
                    .transferOrder(order++)
                    .transferredAt(event.transferredAt() != null ? event.transferredAt().toLocalDateTime() : null)
                    .disconnectedBy(event.disconnectedBy())
                    .targetSwitchCallId(event.targetSwitchCallId())
                    .build());
        }
        transferEventRepository.saveAll(entities);
    }

    public List<CallStatusRef> knownCallRefs() {
        return audioFileRepository.findAllRefsAndStatus();
    }

    /** Uploads pendentes de processamento (Fase 3 do Quality Management, V40) — o
     * serviço Python consulta este método (via endpoint interno) em vez de escanear
     * disco: o Java já sabe exatamente quais arquivos foram enviados e por quem. */
    public List<CallAudioFile> findPendingUploads() {
        return audioFileRepository.findBySourceAndStatus("upload", "pending");
    }

    /**
     * Registra um par .wav+.xml recém-descoberto em /opt/audio, status='pending'. Chamado pelo
     * watcher Python ANTES de entrar na fila de processamento (não no início do processamento em
     * si — ver markProcessing) — só assim a chamada aparece na aba "Processamento" mesmo antes de
     * começar a rodar. Idempotente: se já existir (corrida entre ciclos de poll), não sobrescreve
     * o status atual (evita voltar 'processing'/'done'/'error' pra 'pending' por engano).
     */
    @Transactional
    public void registerPending(String callRef, String wavPath, String xmlPath) {
        boolean isNew = audioFileRepository.findByCallRef(callRef).isEmpty();
        if (!isNew) {
            return;
        }
        audioFileRepository.save(CallAudioFile.builder()
                .callRef(callRef)
                .wavPath(wavPath)
                .xmlPath(xmlPath)
                .status("pending")
                .build());
    }

    /** Registra um arquivo de upload do portal do supervisor (Fase 3 do Quality
     * Management, V40) — chamado diretamente pelo InsightsUploadController no momento do
     * upload (não pelo watcher Python; uploads não passam por registerPending). Sem XML,
     * status='pending', source='upload'. */
    @Transactional
    public void registerUpload(String callRef, String wavPath, String agentName, String direction,
                                String uploadedBy, java.util.UUID uploadBatchId) {
        audioFileRepository.save(CallAudioFile.builder()
                .callRef(callRef)
                .wavPath(wavPath)
                .agentName(agentName)
                .direction(direction)
                .status("pending")
                .source("upload")
                .uploadedBy(uploadedBy)
                .uploadBatchId(uploadBatchId)
                .build());
    }

    /** Gravações do Call Center pendentes de processamento (Fase 8) — o serviço Python
     * consulta este método (via endpoint interno) em vez de escanear disco: o Java já
     * sabe exatamente qual agente/fila atendeu cada gravação (ver
     * CallCenterRecordingService.registerInsights). */
    public List<CallAudioFile> findPendingCallCenterRecordings() {
        return audioFileRepository.findBySourceAndStatus("callcenter", "pending");
    }

    /**
     * Registra uma gravação do Call Center (Fase 8) — chamado por
     * {@code CallCenterRecordingService.ingest} logo após salvar a {@code CcRecording}, na
     * mesma transação. Idempotente por callRef (channelUniqueId prefixado): uma retransmissão do
     * CURL do dialplan não deve duplicar o registro. Diferente do fluxo Verint, o Java já sabe
     * agente/fila (join com {@code cc_interactions}) — sem XML, sem descoberta por regex de nome
     * de arquivo no lado Python.
     */
    @Transactional
    public void registerCallCenterRecording(
            String callRef, String wavPath, String agentName, String queueName, String ani, Long ccRecordingId) {
        if (audioFileRepository.findByCallRef(callRef).isPresent()) {
            return;
        }
        audioFileRepository.save(CallAudioFile.builder()
                .callRef(callRef)
                .wavPath(wavPath)
                .agentName(agentName)
                .direction("inbound")
                .skill(queueName)
                .ani(ani)
                .status("pending")
                .source("callcenter")
                .ccRecordingId(ccRecordingId)
                .build());
    }

    /** Marca o início do processamento de fato (retirada da fila) — chamado no início de
     * process_pair() no watcher Python, tanto pra chamadas novas quanto pra retries de erro. */
    @Transactional
    public void markProcessing(String callRef, String wavPath, String xmlPath) {
        CallAudioFile audioFile = audioFileRepository.findByCallRef(callRef)
                .orElseGet(() -> CallAudioFile.builder().callRef(callRef).build());
        // wavPath/xmlPath só chegam preenchidos quando a chamada nunca foi vista antes
        // (registerPending não rodou) — nunca sobrescrever com null um valor já persistido.
        if (wavPath != null) {
            audioFile.setWavPath(wavPath);
        }
        if (xmlPath != null) {
            audioFile.setXmlPath(xmlPath);
        }
        audioFile.setStatus("processing");
        audioFile.setStartedAt(LocalDateTime.now());
        audioFile.setErrorMsg(null);
        audioFileRepository.save(audioFile);
    }

    /** Marca falha — chamado em qualquer exceção do pipeline Python (parse XML, decode de áudio,
     * falha de IA, falha ao enviar o resultado final). Sem isso, chamadas com erro nunca ficavam
     * visíveis (só nos logs do container) e o watcher as reprocessava para sempre, silenciosamente. */
    @Transactional
    public void markError(String callRef, String errorMsg) {
        CallAudioFile audioFile = audioFileRepository.findByCallRef(callRef)
                .orElseGet(() -> CallAudioFile.builder().callRef(callRef).build());
        audioFile.setStatus("error");
        audioFile.setErrorMsg(errorMsg);
        audioFile.setProcessedAt(LocalDateTime.now());
        audioFileRepository.save(audioFile);
    }

    private String toJsonString(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Falha ao serializar campo JSONB — armazenando como null: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Achado em produção (2026-07-17): o serviço de insights já clampa esse valor antes de
     * enviar, mas este é o boundary real entre dois serviços — nunca confiar apenas na
     * validação do lado de fora. Sem o clamp aqui, um valor fora de [0,1] vindo do LLM
     * (ex: 85 em vez de 0.85) estoura a coluna {@code NUMERIC(4,3)} e derruba a ingestão
     * inteira da chamada com "numeric field overflow".
     */
    private BigDecimal clampAderenciaScript(Double value) {
        if (value == null) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return BigDecimal.valueOf(clamped);
    }
}
