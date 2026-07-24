package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CallTransferEventRepository extends JpaRepository<CallTransferEvent, Long> {

    List<CallTransferEvent> findByAudioFileIdOrderByTransferOrderAsc(Long audioFileId);

    void deleteByAudioFileId(Long audioFileId);

    /** Sentido "esta chamada é destino de uma transferência pendente de outra" —
     * ver TransferResolutionService. */
    List<CallTransferEvent> findByTargetSwitchCallIdAndResolvedAtIsNull(String targetSwitchCallId);

    /** Último evento de transferência de cada chamada da página (por audioFileId),
     * usado pra popular as colunas "Ramal destino"/"Atendente destino" da lista sem
     * N+1 — busca todos os eventos das chamadas da página de uma vez, o serviço
     * escolhe o de maior transferOrder por audioFileId em memória. */
    List<CallTransferEvent> findByAudioFileIdInOrderByAudioFileIdAscTransferOrderAsc(List<Long> audioFileIds);

    /** Filtros "Ramal destino"/"Atendente destino"/ID global (decisão 8) — o campo vive
     * na tabela filha, não em CallAudioFile, por isso resolvido como restrictedIds em
     * InsightsQueryService (mesmo padrão de texto/categoria/tom). */
    @Query("SELECT DISTINCT e.audioFileId FROM CallTransferEvent e WHERE LOWER(e.targetExtension) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<Long> findAudioFileIdsByTargetExtension(@Param("q") String q);

    @Query("SELECT DISTINCT e.audioFileId FROM CallTransferEvent e WHERE LOWER(e.targetAgentName) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<Long> findAudioFileIdsByTargetAgentName(@Param("q") String q);

    /** ADMIN-only — ver gate em InsightsQueryService.resolveRestrictedIds. */
    @Query("SELECT DISTINCT e.audioFileId FROM CallTransferEvent e WHERE e.targetSwitchCallId = :id")
    List<Long> findAudioFileIdsByTargetSwitchCallId(@Param("id") String id);
}
