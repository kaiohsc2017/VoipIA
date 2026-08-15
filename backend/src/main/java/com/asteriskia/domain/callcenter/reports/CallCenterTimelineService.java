package com.asteriskia.domain.callcenter.reports;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterTimelineService — timeline omnicanal (voz + chat) de um contato, paginada em banco
 * (sub-fase 9c.3 do plano modulo-callcenter-omnicanal.plan.md). Diferente do "Perfil do cliente"
 * (Fase 27, {@link CallCenterCustomerProfileService}), que traz agregados/top-assuntos e varre o
 * período inteiro em memória (gap aceito lá, dado o volume desta VPS de dev) — aqui a paginação é
 * feita no PRÓPRIO SQL via {@code LIMIT/OFFSET}, porque este serviço é a base que a Fase 16
 * (copiloto de IA) vai consumir, e um copiloto não pode carregar o histórico inteiro de um
 * cliente com muitos contatos na memória a cada pergunta.
 *
 * <p>A chave do contato ({@link AniNormalizer#normalize}) é replicada em SQL puro (CTEs
 * {@code calls_final}/{@code chats_final}) em vez de reaproveitar o método Java, porque a
 * comparação precisa acontecer DENTRO da query paginada — não há coluna persistida com o valor
 * normalizado (mesmo gap documentado no Perfil do Cliente: sem {@code resolved_ad_sam}, a
 * normalização é a única forma de correlacionar voz e chat). A tradução SQL foi validada
 * manualmente contra {@link AniNormalizer#normalize} para os casos representativos (DDI 55,
 * formatação com símbolos, celular sem o 9º dígito, ramal curto) antes de entrar em produção —
 * ver histórico de validação na sessão que implementou esta fatia.
 */
@Service
public class CallCenterTimelineService {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String CALLS_CTE = """
            calls_norm AS (
                SELECT i.id, i.queued_at, i.queue_id, i.agent_id, i.nps_score, i.disposition_id,
                    regexp_replace(i.ani, '\\D', '', 'g') AS raw_digits
                FROM cc_interactions i
                WHERE i.ani IS NOT NULL AND i.queued_at BETWEEN :fromTs AND :toTs
            ),
            calls_cc AS (
                SELECT *, CASE WHEN length(raw_digits) >= 12 AND left(raw_digits,2)='55'
                    THEN substring(raw_digits from 3) ELSE raw_digits END AS digits_no_cc
                FROM calls_norm
            ),
            calls_final AS (
                SELECT *, CASE WHEN length(digits_no_cc)=10 AND substring(digits_no_cc from 3 for 1) >= '6'
                    THEN left(digits_no_cc,2) || '9' || substring(digits_no_cc from 3) ELSE digits_no_cc END AS normalized_key
                FROM calls_cc
            )
            """;

    private static final String CHATS_CTE = """
            chats_norm AS (
                SELECT s.id, s.started_at, s.queue_id, s.assigned_agent_id AS agent_id, s.disposition_id,
                    regexp_replace(s.customer_ref, '\\D', '', 'g') AS raw_digits
                FROM cc_chat_sessions s
                WHERE s.customer_ref IS NOT NULL AND s.started_at BETWEEN :fromTs AND :toTs
            ),
            chats_cc AS (
                SELECT *, CASE WHEN length(raw_digits) >= 12 AND left(raw_digits,2)='55'
                    THEN substring(raw_digits from 3) ELSE raw_digits END AS digits_no_cc
                FROM chats_norm
            ),
            chats_final AS (
                SELECT *, CASE WHEN length(digits_no_cc)=10 AND substring(digits_no_cc from 3 for 1) >= '6'
                    THEN left(digits_no_cc,2) || '9' || substring(digits_no_cc from 3) ELSE digits_no_cc END AS normalized_key
                FROM chats_cc
            )
            """;

    private static final String COMBINED_CTE = """
            combined AS (
                SELECT 'CALL' AS event_type, id AS event_id, queued_at AS occurred_at,
                    queue_id, agent_id, nps_score, disposition_id
                FROM calls_final WHERE normalized_key = :contactKey
                UNION ALL
                SELECT 'CHAT' AS event_type, id AS event_id, started_at AS occurred_at,
                    queue_id, agent_id, NULL::numeric AS nps_score, disposition_id
                FROM chats_final WHERE normalized_key = :contactKey
            )
            """;

    @Transactional(readOnly = true)
    public Page<TimelineEventRow> timeline(String rawContact, LocalDate from, LocalDate to, Pageable pageable) {
        String contactKey = AniNormalizer.normalize(rawContact);
        if (contactKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contato inválido");
        }
        LocalDateTime fromTs = from.atStartOfDay();
        LocalDateTime toTs = to.atTime(LocalTime.MAX);

        long total = countEvents(contactKey, fromTs, toTs);
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String sql = "WITH " + CALLS_CTE + ", " + CHATS_CTE + ", " + COMBINED_CTE + """
                SELECT c.event_type, c.event_id, c.occurred_at, q.display_name, a.name, c.nps_score, d.label
                FROM combined c
                LEFT JOIN cc_queues q ON q.id = c.queue_id
                LEFT JOIN cc_agents a ON a.id = c.agent_id
                LEFT JOIN cc_dispositions d ON d.id = c.disposition_id
                ORDER BY c.occurred_at DESC
                LIMIT :limit OFFSET :offset
                """;
        Query query = entityManager.createNativeQuery(sql)
                .setParameter("fromTs", fromTs)
                .setParameter("toTs", toTs)
                .setParameter("contactKey", contactKey)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<TimelineEventRow> events = rows.stream().map(this::toRow).toList();
        return new PageImpl<>(events, pageable, total);
    }

    private long countEvents(String contactKey, LocalDateTime fromTs, LocalDateTime toTs) {
        String sql = "WITH " + CALLS_CTE + ", " + CHATS_CTE + ", " + COMBINED_CTE
                + " SELECT count(*) FROM combined";
        Object result = entityManager.createNativeQuery(sql)
                .setParameter("fromTs", fromTs)
                .setParameter("toTs", toTs)
                .setParameter("contactKey", contactKey)
                .getSingleResult();
        return ((Number) result).longValue();
    }

    private TimelineEventRow toRow(Object[] row) {
        return new TimelineEventRow(
                (String) row[0],
                ((Number) row[1]).longValue(),
                ((java.sql.Timestamp) row[2]).toLocalDateTime(),
                (String) row[3],
                (String) row[4],
                row[5] != null ? new BigDecimal(row[5].toString()) : null,
                (String) row[6]);
    }
}
