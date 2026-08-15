package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.chat.CcChatMessage;
import com.asteriskia.domain.callcenter.chat.CcChatMessageRepository;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterChatAggregationService — agregado diário de chat (sub-fase 9c.2 do plano
 * modulo-callcenter-omnicanal.plan.md): FRT (first response time), ART (average response time),
 * concorrência média (sweep-line ponderado pelo tempo) e contenção do bot. Mesmo padrão de
 * upsert-por-(fila,dia) de {@link CallCenterQueueAggregationService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterChatAggregationService {

    private static final long MAX_REPROCESS_DAYS = 400;

    private final CcQueueRepository queueRepository;
    private final CcChatSessionRepository sessionRepository;
    private final CcChatMessageRepository messageRepository;
    private final CcAggChatDailyRepository aggRepository;

    @Transactional
    public void aggregateDate(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.atTime(LocalTime.MAX);
        for (CcQueue queue : queueRepository.findByActiveTrue()) {
            aggregateQueueDate(queue, date, from, to);
        }
        log.info("Agregado diário de chat recalculado para {}", date);
    }

    private void aggregateQueueDate(CcQueue queue, LocalDate date, LocalDateTime from, LocalDateTime to) {
        List<CcChatSession> sessions = sessionRepository.findByQueueIdAndStartedAtBetween(queue.getId(), from, to);

        int received = sessions.size();
        int claimed = (int) sessions.stream().filter(s -> s.getClaimedAt() != null).count();
        int closed = (int) sessions.stream().filter(s -> s.getClosedAt() != null).count();
        int botContained = (int) sessions.stream()
                .filter(s -> hasBotFlow(s) && s.getClaimedAt() == null && s.getClosedAt() != null)
                .count();
        int botEscalated = (int) sessions.stream()
                .filter(s -> hasBotFlow(s) && s.getClaimedAt() != null)
                .count();

        List<CcChatMessage> messages = fetchMessagesForSessions(sessions);
        Map<Long, List<CcChatMessage>> messagesBySession = messages.stream()
                .collect(Collectors.groupingBy(CcChatMessage::getSessionId));

        BigDecimal avgFrtSeconds = averageFirstResponseTime(sessions, messagesBySession);
        BigDecimal avgResponseSeconds = averageResponseTime(sessions, messagesBySession);
        BigDecimal avgConcurrentChats = averageConcurrency(sessions, from, to);

        CcAggChatDaily agg = aggRepository.findByQueueIdAndDate(queue.getId(), date)
                .orElseGet(() -> CcAggChatDaily.builder().queue(queue).date(date).build());
        agg.setQueue(queue);
        agg.setBusinessUnit(queue.getBusinessUnit());
        agg.setReceived(received);
        agg.setClaimed(claimed);
        agg.setClosed(closed);
        agg.setBotContained(botContained);
        agg.setBotEscalated(botEscalated);
        agg.setAvgFrtSeconds(avgFrtSeconds);
        agg.setAvgResponseSeconds(avgResponseSeconds);
        agg.setAvgConcurrentChats(avgConcurrentChats);
        agg.setComputedAt(LocalDateTime.now());
        aggRepository.save(agg);
    }

    private boolean hasBotFlow(CcChatSession session) {
        return session.getChannel() != null && session.getChannel().getBotFlow() != null;
    }

    /** Janela de busca das mensagens: do início da sessão mais antiga do lote ao fechamento da
     * mais recente (ou "agora", se ainda aberta), com folga de 1 dia — habilita pruning de
     * partição sem assumir que toda sessão de chat encerra no mesmo dia em que começou. */
    private List<CcChatMessage> fetchMessagesForSessions(List<CcChatSession> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<Long> sessionIds = sessions.stream().map(CcChatSession::getId).toList();
        LocalDateTime windowFrom = sessions.stream().map(CcChatSession::getStartedAt)
                .min(LocalDateTime::compareTo).orElseThrow().minusDays(1);
        LocalDateTime windowTo = sessions.stream()
                .map(s -> s.getClosedAt() != null ? s.getClosedAt() : LocalDateTime.now())
                .max(LocalDateTime::compareTo).orElseThrow().plusDays(1);
        return messageRepository.findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtAsc(sessionIds, windowFrom, windowTo);
    }

    /** FRT — média de (primeira mensagem de agente) - startedAt, só das sessões com resposta de
     * agente. Mensagens de bot não contam como "primeira resposta" para este indicador — FRT mede
     * o tempo até um humano responder. */
    private BigDecimal averageFirstResponseTime(List<CcChatSession> sessions, Map<Long, List<CcChatMessage>> messagesBySession) {
        List<Long> seconds = new ArrayList<>();
        for (CcChatSession session : sessions) {
            List<CcChatMessage> msgs = messagesBySession.getOrDefault(session.getId(), List.of());
            msgs.stream()
                    .filter(m -> "agent".equals(m.getSenderType()))
                    .findFirst()
                    .ifPresent(first -> seconds.add(Duration.between(session.getStartedAt(), first.getCreatedAt()).toSeconds()));
        }
        return average(seconds);
    }

    /** ART — média do intervalo entre uma mensagem do cliente e a próxima resposta (agente ou
     * bot), considerando todas as sessões do lote. */
    private BigDecimal averageResponseTime(List<CcChatSession> sessions, Map<Long, List<CcChatMessage>> messagesBySession) {
        List<Long> seconds = new ArrayList<>();
        for (CcChatSession session : sessions) {
            List<CcChatMessage> msgs = messagesBySession.getOrDefault(session.getId(), List.of());
            CcChatMessage pendingCustomerMessage = null;
            for (CcChatMessage msg : msgs) {
                if ("customer".equals(msg.getSenderType())) {
                    pendingCustomerMessage = msg;
                } else if (pendingCustomerMessage != null) {
                    seconds.add(Duration.between(pendingCustomerMessage.getCreatedAt(), msg.getCreatedAt()).toSeconds());
                    pendingCustomerMessage = null;
                }
            }
        }
        return average(seconds);
    }

    /** Concorrência média ponderada pelo tempo (sweep-line): soma a área sob a curva "número de
     * sessões simultaneamente abertas" ao longo do dia, dividida pela duração do dia. Sessão ainda
     * aberta ao fim do dia conta até o fim do dia (não até "agora"), para o cálculo não variar
     * conforme o instante em que o agregado é recalculado. */
    private BigDecimal averageConcurrency(List<CcChatSession> sessions, LocalDateTime dayStart, LocalDateTime dayEnd) {
        if (sessions.isEmpty()) {
            return null;
        }
        record Event(LocalDateTime time, int delta) {}
        List<Event> events = new ArrayList<>();
        for (CcChatSession session : sessions) {
            LocalDateTime start = clamp(session.getStartedAt(), dayStart, dayEnd);
            LocalDateTime end = clamp(session.getClosedAt() != null ? session.getClosedAt() : dayEnd, dayStart, dayEnd);
            if (!end.isAfter(start)) {
                continue;
            }
            events.add(new Event(start, 1));
            events.add(new Event(end, -1));
        }
        if (events.isEmpty()) {
            return BigDecimal.ZERO;
        }
        events.sort((a, b) -> a.time().compareTo(b.time()));

        long totalAreaSeconds = 0;
        int current = 0;
        LocalDateTime previousTime = events.get(0).time();
        for (Event event : events) {
            totalAreaSeconds += (long) current * Duration.between(previousTime, event.time()).toSeconds();
            current += event.delta();
            previousTime = event.time();
        }
        long daySeconds = Duration.between(dayStart, dayEnd).toSeconds();
        if (daySeconds == 0) {
            return null;
        }
        return BigDecimal.valueOf(totalAreaSeconds)
                .divide(BigDecimal.valueOf(daySeconds), 2, RoundingMode.HALF_UP);
    }

    private LocalDateTime clamp(LocalDateTime value, LocalDateTime min, LocalDateTime max) {
        if (value.isBefore(min)) {
            return min;
        }
        if (value.isAfter(max)) {
            return max;
        }
        return value;
    }

    private BigDecimal average(List<Long> secondsValues) {
        if (secondsValues.isEmpty()) {
            return null;
        }
        long sum = secondsValues.stream().mapToLong(Long::longValue).sum();
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(secondsValues.size()), 2, RoundingMode.HALF_UP);
    }

    /** Ver {@code CallCenterQueueAggregationService.reprocessRange} — mesma convenção. */
    public void reprocessRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data final não pode ser anterior à inicial.");
        }
        if (Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > MAX_REPROCESS_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Intervalo maior que " + MAX_REPROCESS_DAYS + " dias — reprocesse em lotes menores.");
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            aggregateDate(date);
        }
    }
}
