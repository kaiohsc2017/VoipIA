package com.asteriskia.domain.callcenter.copilot;

import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterContactHistoryService — histórico unificado voz+chat de um contato identificado
 * (Fase 16.1), por {@code resolved_ad_sam} (Fase 14) — deliberadamente diferente do {@code
 * CallCenterTimelineService} (Fase 9c.3, relatório paginado por período e correlacionado por ANI
 * normalizado): aqui a chave é a identidade resolvida (mais precisa que ANI, que varia entre
 * celular/fixo/chat do mesmo contato) e o volume é sempre pequeno (últimos N contatos), nunca
 * paginado — é consulta de hot-path do atendimento em curso, não um relatório de supervisor.
 *
 * <p><b>Gap aceito, documentado</b>: sem escopo por BU (mesmo padrão já aceito no restante do
 * domínio Call Center — Alertas Zabbix, Insights, Perfil do Cliente da Fase 27) — um agente vê
 * histórico do contato independente da BU de cada atendimento anterior.
 *
 * <p>Cache em memória de 45s por {@code resolved_ad_sam} (mesmo padrão de TTL curto do {@code
 * UraRoutingService}) — numa fila movimentada, o mesmo contato pode ser puxado várias vezes em
 * sequência (screen pop, painel de perfil, prompt do copiloto). O cache nunca faz eviction de
 * chaves antigas (cresce com o número de contatos distintos consultados ao longo do uptime do
 * processo) — mesmo padrão já aceito para o {@code UraRoutingService}, tolerável na escala atual.
 *
 * <p><b>Gap aceito, documentado</b>: {@code fetchAll} traz o histórico COMPLETO e sem paginação
 * de um contato antes do corte por {@code limit} acontecer em memória — para um contato com anos
 * de atendimentos, isso cresce por chamada (e por entrada de cache). Aceitável no volume atual
 * desta VPS de dev; revisitar com {@code Top10}/paginação real no banco se o volume crescer
 * (mesma classe de gap já aceita em outras partes do domínio Call Center).
 */
@Service
@RequiredArgsConstructor
public class CallCenterContactHistoryService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(45);

    private final CcInteractionRepository interactionRepository;
    private final CcChatSessionRepository chatSessionRepository;

    private final Map<String, CachedHistory> cache = new ConcurrentHashMap<>();

    private record CachedHistory(Instant fetchedAt, List<ContactHistoryItem> items) {}

    /** Últimos {@code limit} contatos do sam informado, mais recente primeiro, excluindo o
     * contato atual (interação ou sessão de chat) quando informado. */
    @Transactional(readOnly = true)
    public List<ContactHistoryItem> historyFor(
            String resolvedAdSam, int limit, Long excludeInteractionId, Long excludeChatSessionId) {
        if (resolvedAdSam == null || resolvedAdSam.isBlank()) {
            return List.of();
        }
        List<ContactHistoryItem> all = cache
                .compute(
                        resolvedAdSam,
                        (sam, cached) -> {
                            if (cached != null
                                    && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
                                return cached;
                            }
                            return new CachedHistory(Instant.now(), fetchAll(sam));
                        })
                .items();

        return all.stream()
                .filter(item -> !("voz".equals(item.channel()) && item.referenceId().equals(excludeInteractionId)))
                .filter(item -> !("chat".equals(item.channel()) && item.referenceId().equals(excludeChatSessionId)))
                .limit(limit)
                .toList();
    }

    private List<ContactHistoryItem> fetchAll(String resolvedAdSam) {
        List<ContactHistoryItem> calls = interactionRepository.findByResolvedAdSamOrderByQueuedAtDesc(resolvedAdSam)
                .stream()
                .map(this::toItem)
                .toList();
        List<ContactHistoryItem> chats = chatSessionRepository.findByResolvedAdSamOrderByStartedAtDesc(resolvedAdSam)
                .stream()
                .map(this::toItem)
                .toList();
        return java.util.stream.Stream.concat(calls.stream(), chats.stream())
                .sorted(Comparator.comparing(ContactHistoryItem::startedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private ContactHistoryItem toItem(CcInteraction i) {
        return new ContactHistoryItem(
                "voz",
                i.getId(),
                i.getQueue() == null ? null : i.getQueue().getDisplayName(),
                i.getAgent() == null ? null : i.getAgent().getName(),
                i.getQueuedAt(),
                i.getEndedAt(),
                i.getDisposition() == null ? null : i.getDisposition().getLabel());
    }

    private ContactHistoryItem toItem(CcChatSession s) {
        return new ContactHistoryItem(
                "chat",
                s.getId(),
                s.getQueue() == null ? null : s.getQueue().getDisplayName(),
                s.getAssignedAgent() == null ? null : s.getAssignedAgent().getName(),
                s.getStartedAt(),
                s.getClosedAt(),
                s.getDisposition() == null ? null : s.getDisposition().getLabel());
    }
}
