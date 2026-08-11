package com.asteriskia.domain;

import com.asteriskia.domain.call.CallRecord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StatsCallRepository extends JpaRepository<CallRecord, Long> {

    // Fragmento repetido nas 5 queries de ranking abaixo (topClients, byCallType,
    // topResolutions, topSubjectsByCallType, avgDurationByCallType): join com uras +
    // filtro de período + escopo por BU (mesmo padrão de
    // CallRecordSpecifications.restrictedToBusinessUnits) + filtro opcional por URA.
    // Extraído como constante em vez de Criteria API — migrar a Criteria API teria risco
    // de regressão maior sem suíte de testes de integração cobrindo essas queries.
    String BU_URA_JOIN_PREFIX =
            "JOIN uras u ON u.id = c.ura_id " + "WHERE c.call_date BETWEEN :from AND :to AND ";
    String BU_URA_SCOPE_SUFFIX =
            "AND (:restricted = false OR u.business_unit_id IS NULL "
                    + "OR u.business_unit_id IN (:buIds)) "
                    + "AND (:uraId IS NULL OR c.ura_id = :uraId) ";

    @Query("SELECT COUNT(c) FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // nativeQuery=true evita o erro "could not determine data type of parameter $N"
    // que o Hibernate 6 gera com IS NOT NULL em JPQL sobre colunas TEXT nullable no PostgreSQL
    @Query(
            value =
                    "SELECT COUNT(*) FROM call_records c WHERE c.jira_issue_key IS NOT NULL "
                            + "AND c.call_date BETWEEN :from AND :to",
            nativeQuery = true)
    long countWithJiraByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            value =
                    "SELECT COUNT(*) FROM call_records c WHERE c.transcription IS NOT NULL "
                            + "AND c.call_date BETWEEN :from AND :to",
            nativeQuery = true)
    long countWithTranscriptionByPeriod(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "SELECT COALESCE(AVG(c.callDurationSecs), 0) FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to")
    double avgDurationByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "SELECT CAST(c.callDate AS date) as day, COUNT(c), "
                    + "SUM(CASE WHEN c.jiraIssueKey IS NOT NULL THEN 1 ELSE 0 END), "
                    + "AVG(c.callDurationSecs) "
                    + "FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to "
                    + "GROUP BY CAST(c.callDate AS date) ORDER BY day")
    List<Object[]> countByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // nativeQuery=true — mesmo motivo do countWithJiraByPeriod acima: JPQL com
    // IS NOT NULL sobre coluna nullable no Postgres quebra a inferência de tipo
    // do Hibernate 6 nesse projeto.
    //
    // Escopo por BU (mesmo padrão de CallRecordSpecifications.restrictedToBusinessUnits):
    // join com uras para restringir às BUs do usuário quando "restricted" — URAs sem BU
    // definida ficam visíveis a todos. "buIds" nunca é enviado vazio pelo controller
    // (usa sentinela {-1} quando o usuário não tem BU nenhuma), evitando IN () vazio.
    @Query(
            value =
                    "SELECT c.client_name AS label, COUNT(*) AS total FROM call_records c "
                            + BU_URA_JOIN_PREFIX
                            + "c.client_name IS NOT NULL "
                            + BU_URA_SCOPE_SUFFIX
                            + "GROUP BY c.client_name ORDER BY total DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topClients(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") int limit,
            @Param("restricted") boolean restricted,
            @Param("buIds") Set<Integer> buIds,
            @Param("uraId") Integer uraId);

    @Query(
            value =
                    "SELECT c.call_type AS label, COUNT(*) AS total FROM call_records c "
                            + BU_URA_JOIN_PREFIX
                            + "c.call_type IS NOT NULL "
                            + BU_URA_SCOPE_SUFFIX
                            + "GROUP BY c.call_type ORDER BY total DESC",
            nativeQuery = true)
    List<Object[]> byCallType(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("restricted") boolean restricted,
            @Param("buIds") Set<Integer> buIds,
            @Param("uraId") Integer uraId);

    @Query(
            value =
                    "SELECT c.jira_resolution AS label, COUNT(*) AS total FROM call_records c "
                            + BU_URA_JOIN_PREFIX
                            + "c.jira_resolution IS NOT NULL "
                            + BU_URA_SCOPE_SUFFIX
                            + "GROUP BY c.jira_resolution ORDER BY total DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topResolutions(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") int limit,
            @Param("restricted") boolean restricted,
            @Param("buIds") Set<Integer> buIds,
            @Param("uraId") Integer uraId);

    // Assunto mais pedido (subject_tag, classificado por IA no ai-agent) dentro de um
    // call_type específico (ex: "Incidente" ou "Requisição") — alimenta o indicador
    // "mais pedido" por tipo na aba Ranking de Atendimentos.
    @Query(
            value =
                    "SELECT c.subject_tag AS label, COUNT(*) AS total FROM call_records c "
                            + BU_URA_JOIN_PREFIX
                            + "c.call_type = :callType AND c.subject_tag IS NOT NULL "
                            + BU_URA_SCOPE_SUFFIX
                            + "GROUP BY c.subject_tag ORDER BY total DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topSubjectsByCallType(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("callType") String callType,
            @Param("limit") int limit,
            @Param("restricted") boolean restricted,
            @Param("buIds") Set<Integer> buIds,
            @Param("uraId") Integer uraId);

    // Duração média por tipo de chamada — indicador "duração média" por tipo na
    // aba Ranking de Atendimentos (item 9 do backlog de melhorias).
    @Query(
            value =
                    "SELECT c.call_type AS label, AVG(c.call_duration_secs) AS total FROM call_records c "
                            + BU_URA_JOIN_PREFIX
                            + "c.call_type IS NOT NULL "
                            + BU_URA_SCOPE_SUFFIX
                            + "GROUP BY c.call_type ORDER BY total DESC",
            nativeQuery = true)
    List<Object[]> avgDurationByCallType(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("restricted") boolean restricted,
            @Param("buIds") Set<Integer> buIds,
            @Param("uraId") Integer uraId);
}
