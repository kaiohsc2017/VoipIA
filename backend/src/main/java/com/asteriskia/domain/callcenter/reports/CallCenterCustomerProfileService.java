package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.recording.CcRecording;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallInsight;
import com.asteriskia.domain.insights.CallInsightRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterCustomerProfileService — "Perfil do cliente" (Fase 27): quem mais liga/conversa,
 * histórico de contatos e top assuntos, agrupado por {@link AniNormalizer}. GAP CONHECIDO: sem
 * {@code resolved_ad_sam} (Fase 14, inexistente no código), voz (ANI) e chat (customerRef) só se
 * correlacionam quando os dois normalizam para o mesmo dígito — nunca há garantia disso quando o
 * chat não recebe um telefone como referência. Sem cooldown/persistência — consulta agregada
 * on-the-fly sobre dado já existente, mesmo espírito de {@link CallCenterReportsQueryService}.
 *
 * <p>Sem paginação em banco na varredura do período (mesma decisão já aceita em
 * {@code CallCenterDetailReportService#searchChats} para volume baixo de dev) — revisitar se o
 * volume de interações crescer.
 */
@Service
@RequiredArgsConstructor
public class CallCenterCustomerProfileService {

    private final CcInteractionRepository interactionRepository;
    private final CcChatSessionRepository chatSessionRepository;
    private final CcRecordingRepository recordingRepository;
    private final CallAudioFileRepository audioFileRepository;
    private final CallInsightRepository insightRepository;

    @Transactional(readOnly = true)
    public Page<CustomerProfileSummaryRow> search(LocalDate from, LocalDate to, Pageable pageable) {
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        Map<String, List<CcInteraction>> byClientCalls = interactionRepository.findByQueuedAtBetween(fromDt, toDt)
                .stream()
                .filter(i -> i.getAni() != null && !i.getAni().isBlank())
                .collect(Collectors.groupingBy(i -> AniNormalizer.normalize(i.getAni())));
        Map<String, List<CcChatSession>> byClientChats = chatSessionRepository.findByStartedAtBetween(fromDt, toDt)
                .stream()
                .filter(s -> s.getCustomerRef() != null && !s.getCustomerRef().isBlank())
                .collect(Collectors.groupingBy(s -> AniNormalizer.normalize(s.getCustomerRef())));

        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(byClientCalls.keySet());
        ids.addAll(byClientChats.keySet());

        List<CustomerProfileSummaryRow> all = ids.stream()
                .map(id -> summarize(id, byClientCalls.getOrDefault(id, List.of()), byClientChats.getOrDefault(id, List.of())))
                .sorted(Comparator.comparingInt((CustomerProfileSummaryRow r) -> r.totalChamadas() + r.totalChats()).reversed())
                .toList();

        int start = Math.min((int) pageable.getOffset(), all.size());
        int end = Math.min(start + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(start, end), pageable, all.size());
    }

    @Transactional(readOnly = true)
    public CustomerProfileDetail detail(String rawContact, LocalDate from, LocalDate to) {
        String normalizedId = AniNormalizer.normalize(rawContact);
        if (normalizedId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contato inválido");
        }
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        List<CcInteraction> calls = interactionRepository.findByQueuedAtBetween(fromDt, toDt).stream()
                .filter(i -> normalizedId.equals(AniNormalizer.normalize(i.getAni())))
                .sorted(Comparator.comparing(CcInteraction::getQueuedAt).reversed())
                .toList();
        List<CcChatSession> chats = chatSessionRepository.findByStartedAtBetween(fromDt, toDt).stream()
                .filter(s -> normalizedId.equals(AniNormalizer.normalize(s.getCustomerRef())))
                .sorted(Comparator.comparing(CcChatSession::getStartedAt).reversed())
                .toList();

        if (calls.isEmpty() && chats.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhum contato encontrado nesse período");
        }

        Map<String, Long> assuntoCounts = new HashMap<>();
        List<CustomerProfileDetail.InteractionSummary> chamadaSummaries = new ArrayList<>();
        for (CcInteraction interaction : calls) {
            String categoriaAssunto = resolveCategoriaAssunto(interaction);
            String dispositionLabel = interaction.getDisposition() != null ? interaction.getDisposition().getLabel() : null;
            if (dispositionLabel != null) {
                assuntoCounts.merge(dispositionLabel, 1L, Long::sum);
            } else if (categoriaAssunto != null) {
                assuntoCounts.merge(categoriaAssunto, 1L, Long::sum);
            }
            chamadaSummaries.add(new CustomerProfileDetail.InteractionSummary(
                    interaction.getId(),
                    interaction.getQueuedAt(),
                    queueDisplayName(interaction),
                    interaction.getAgent() != null ? interaction.getAgent().getName() : null,
                    interaction.getNpsScore(),
                    dispositionLabel,
                    categoriaAssunto));
        }

        List<CustomerProfileDetail.ChatSummary> chatSummaries = chats.stream()
                .map(session -> new CustomerProfileDetail.ChatSummary(
                        session.getId(),
                        session.getStartedAt(),
                        session.getQueue() != null
                                ? (session.getQueue().getDisplayName() != null
                                        ? session.getQueue().getDisplayName() : session.getQueue().getName())
                                : null,
                        session.getAssignedAgent() != null ? session.getAssignedAgent().getName() : null,
                        session.getDisposition() != null ? session.getDisposition().getLabel() : null))
                .toList();

        List<CustomerProfileDetail.SubjectCount> topAssuntos = assuntoCounts.entrySet().stream()
                .map(e -> new CustomerProfileDetail.SubjectCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(CustomerProfileDetail.SubjectCount::total).reversed())
                .limit(5)
                .toList();

        BigDecimal npsMedio = averageNps(calls);
        String displayContact = !calls.isEmpty() ? calls.get(0).getAni()
                : !chats.isEmpty() ? chats.get(0).getCustomerRef() : normalizedId;

        return new CustomerProfileDetail(
                normalizedId, displayContact, calls.size(), chats.size(), npsMedio, topAssuntos, chamadaSummaries, chatSummaries);
    }

    private CustomerProfileSummaryRow summarize(String normalizedId, List<CcInteraction> calls, List<CcChatSession> chats) {
        // findByQueuedAtBetween/findByStartedAtBetween não garantem ordem (sem OrderBy na
        // assinatura) — nunca assumir que o último elemento da lista é o contato mais recente,
        // sempre buscar o máximo explicitamente por timestamp (mesmo cuidado já aplicado em
        // detail(), que ordena antes de usar).
        String displayContact = !calls.isEmpty()
                ? calls.stream().max(Comparator.comparing(CcInteraction::getQueuedAt)).map(CcInteraction::getAni).orElse(null)
                : !chats.isEmpty()
                        ? chats.stream().max(Comparator.comparing(CcChatSession::getStartedAt)).map(CcChatSession::getCustomerRef).orElse(null)
                        : normalizedId;

        LocalDateTime primeiro = null;
        LocalDateTime ultimo = null;
        for (CcInteraction i : calls) {
            primeiro = minNullable(primeiro, i.getQueuedAt());
            ultimo = maxNullable(ultimo, i.getQueuedAt());
        }
        for (CcChatSession s : chats) {
            primeiro = minNullable(primeiro, s.getStartedAt());
            ultimo = maxNullable(ultimo, s.getStartedAt());
        }

        BigDecimal npsMedio = averageNps(calls);

        String topAssunto = calls.stream()
                .map(i -> i.getDisposition() != null ? i.getDisposition().getLabel() : null)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(label -> label, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        return new CustomerProfileSummaryRow(
                normalizedId, displayContact, calls.size(), chats.size(), primeiro, ultimo, npsMedio, topAssunto);
    }

    private BigDecimal averageNps(List<CcInteraction> calls) {
        List<BigDecimal> scores = calls.stream().map(CcInteraction::getNpsScore).filter(java.util.Objects::nonNull).toList();
        if (scores.isEmpty()) {
            return null;
        }
        BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP);
    }

    private String queueDisplayName(CcInteraction interaction) {
        if (interaction.getQueue() == null) {
            return null;
        }
        return interaction.getQueue().getDisplayName() != null
                ? interaction.getQueue().getDisplayName() : interaction.getQueue().getName();
    }

    /** Mesma cadeia cc_recordings → call_audio_files → call_insights usada em
     * {@code CallCenterDetailReportService} — leitura, nunca reprocessa nada. */
    private String resolveCategoriaAssunto(CcInteraction interaction) {
        CcRecording recording = recordingRepository.findByInteractionId(interaction.getId()).orElse(null);
        if (recording == null) {
            return null;
        }
        CallAudioFile audioFile = audioFileRepository.findByCcRecordingId(recording.getId()).orElse(null);
        if (audioFile == null) {
            return null;
        }
        return insightRepository.findByAudioFileId(audioFile.getId()).map(CallInsight::getCategoriaAssunto).orElse(null);
    }

    private LocalDateTime minNullable(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private LocalDateTime maxNullable(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
