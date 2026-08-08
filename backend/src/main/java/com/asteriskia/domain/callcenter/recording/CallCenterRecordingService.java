package com.asteriskia.domain.callcenter.recording;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.insights.InsightsIngestionService;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterRecordingService — ingestão/listagem/streaming das gravações MixMonitor das filas do
 * Call Center (Fase 3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterRecordingService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final CcRecordingRepository recordingRepository;
    private final CcQueueRepository queueRepository;
    private final CcInteractionRepository interactionRepository;
    private final InsightsIngestionService insightsIngestionService;

    @Value("${app.callcenter.recording-path:/opt/gravacoes/audio}")
    private String recordingBasePath;

    /**
     * Config de gravação/consentimento de uma fila, no formato {@code record=<bool>[;consent=<path>]}
     * consumido pelo dialplan via CUT/FIELDQTY (não é JSON — é o mesmo estilo de CURL usado por
     * ura-routing). Fila não encontrada, sem fila ou qualquer erro caem em "record=true" — a
     * chamada nunca deixa de ser gravada por falha de rede/config transitória.
     */
    @Transactional(readOnly = true)
    public String queueRecordingConfigText(String extension) {
        return queueRepository
                .findByName(extension)
                .map(
                        queue -> {
                            boolean record = Boolean.TRUE.equals(queue.getRecordingEnabled());
                            if (!record) {
                                return "record=false";
                            }
                            String consent = queue.getConsentMessagePath();
                            return (consent == null || consent.isBlank())
                                    ? "record=true"
                                    : "record=true;consent=" + consent;
                        })
                .orElse("record=true");
    }

    /**
     * Grava a gravação ao final da chamada de fila. Idempotente por {@code channelUniqueId}: o
     * dialplan só chama uma vez, mas uma retransmissão de CURL (timeout do lado do Asterisk com a
     * resposta do backend chegando depois) não deve gerar duplicata nem 500.
     */
    @Transactional
    public CcRecording ingest(
            String channelUniqueId, String extension, String filePath, boolean consentPlayed) {
        Optional<CcRecording> existing = recordingRepository.findByChannelUniqueId(channelUniqueId);
        if (existing.isPresent()) {
            log.info("Ingestão de gravação duplicada ignorada: channelUniqueId={}", channelUniqueId);
            return existing.get();
        }

        CcQueue queue = queueRepository.findByName(extension).orElse(null);
        CcInteraction interaction = interactionRepository.findByChannelUniqueId(channelUniqueId).orElse(null);
        LocalDateTime startedAt = parseStartedAt(channelUniqueId);
        LocalDateTime endedAt = LocalDateTime.now();

        CcRecording recording =
                CcRecording.builder()
                        .queue(queue)
                        .queueExtension(extension)
                        .channelUniqueId(channelUniqueId)
                        .interactionId(interaction != null ? interaction.getId() : null)
                        .filePath(filePath)
                        .businessUnit(queue != null ? queue.getBusinessUnit() : null)
                        .consentPlayed(consentPlayed)
                        .startedAt(startedAt)
                        .endedAt(endedAt)
                        .durationSeconds(clampNonNegative(Duration.between(startedAt, endedAt).getSeconds()))
                        .build();

        recording = recordingRepository.save(recording);
        log.info(
                "Gravação registrada: id={} fila={} channelUniqueId={}",
                recording.getId(),
                extension,
                channelUniqueId);

        registerInsights(recording, queue, interaction);
        return recording;
    }

    /**
     * Registra a gravação no pipeline de Insights (Fase 8) — nunca deve derrubar a resposta do
     * CURL do dialplan: falha aqui só é logada, a gravação em si já está persistida acima.
     */
    private void registerInsights(CcRecording recording, CcQueue queue, CcInteraction interaction) {
        try {
            // Nunca repassa recording.getFilePath() cru para o serviço Python — esse valor
            // veio do parâmetro filePath do dialplan (não confiável, mesma razão pela qual
            // resolveAudioFile abaixo nunca o usa para acesso a disco). Resolve o caminho
            // seguro (nome-base + subpasta derivada de startedAt, canonicalizado dentro de
            // recordingBasePath) e usa ESSE como wavPath armazenado.
            File resolved = resolveAudioFile(recording);
            if (resolved == null) {
                log.warn(
                        "Gravação id={} com filePath fora do diretório esperado — não registrada no Insights",
                        recording.getId());
                return;
            }
            String agentName = interaction != null && interaction.getAgent() != null
                    ? interaction.getAgent().getName()
                    : null;
            String queueName = queue != null
                    ? (queue.getDisplayName() != null ? queue.getDisplayName() : queue.getName())
                    : null;
            String ani = interaction != null ? interaction.getAni() : null;
            insightsIngestionService.registerCallCenterRecording(
                    "cc-" + recording.getChannelUniqueId(),
                    resolved.getAbsolutePath(),
                    agentName,
                    queueName,
                    ani,
                    recording.getId());
        } catch (Exception e) {
            log.warn(
                    "Falha ao registrar gravação id={} no pipeline de Insights: {}",
                    recording.getId(),
                    e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<CcRecording> findRecordings(
            Long queueId, LocalDateTime dateFrom, LocalDateTime dateTo, Pageable pageable) {
        Specification<CcRecording> spec = Specification.where(null);
        if (queueId != null) {
            spec = spec.and(CcRecordingSpecifications.queueIdEquals(queueId));
        }
        if (dateFrom != null) {
            spec = spec.and(CcRecordingSpecifications.startedAtFrom(dateFrom));
        }
        if (dateTo != null) {
            spec = spec.and(CcRecordingSpecifications.startedAtTo(dateTo));
        }
        if (BusinessUnitContext.isRestricted()) {
            spec =
                    spec.and(
                            CcRecordingSpecifications.restrictedToBusinessUnits(
                                    BusinessUnitContext.currentBusinessUnitIds()));
        }
        return recordingRepository.findAll(spec, pageable);
    }

    /**
     * Busca já aplicando o escopo de BU — usada pelo streaming de áudio. Retorna vazio (nunca
     * lança) tanto para id inexistente quanto para gravação fora do escopo do usuário: o
     * controller devolve 404 nos dois casos, para não vazar a existência do registro.
     */
    @Transactional(readOnly = true)
    public Optional<CcRecording> findByIdInScope(Long id) {
        return recordingRepository
                .findById(id)
                .filter(
                        rec ->
                                !BusinessUnitContext.isRestricted()
                                        || rec.getBusinessUnit() == null
                                        || BusinessUnitContext.currentBusinessUnitIds()
                                                .contains(rec.getBusinessUnit().getId()));
    }

    /**
     * Resolve o arquivo físico da gravação com defesa de path traversal: usa só o nome-base do
     * arquivo (nunca o caminho completo gravado em {@code filePath}) e reconstrói o subdiretório
     * ano/mês/dia a partir de {@code startedAt} (campo confiável do banco, não vindo de input
     * externo), validando o caminho canônico contra escape — mesmo padrão de
     * {@code CallRecordController.resolveWithinBase}.
     */
    public File resolveAudioFile(CcRecording recording) {
        String fileName = new File(recording.getFilePath()).getName();
        String subPath = recording.getStartedAt().format(YMD);
        try {
            File base = new File(recordingBasePath).getCanonicalFile();
            File target = new File(new File(base, subPath), fileName).getCanonicalFile();
            String basePath = base.getPath() + File.separator;
            if (target.getPath().equals(base.getPath()) || target.getPath().startsWith(basePath)) {
                return target;
            }
        } catch (IOException e) {
            log.warn("Erro ao resolver caminho de gravação id={}: {}", recording.getId(), e.getMessage());
        }
        return null;
    }

    private static LocalDateTime parseStartedAt(String channelUniqueId) {
        // UNIQUEID do Asterisk tem o formato "<epoch>.<sequencial>" — usa o epoch como o
        // instante real de criação do canal (aproximação razoável do início da gravação,
        // já que MixMonitor(b) é a primeira ação após Answer() no dialplan).
        try {
            String epochPart = channelUniqueId.split("\\.")[0];
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(epochPart)), ZoneId.systemDefault());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private static Integer clampNonNegative(long seconds) {
        return (int) Math.max(0, seconds);
    }
}
