package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CallAudioFileRepository
        extends JpaRepository<CallAudioFile, Long>, JpaSpecificationExecutor<CallAudioFile> {

    Optional<CallAudioFile> findByCallRef(String callRef);

    @Query("SELECT new com.asteriskia.domain.insights.CallStatusRef(c.callRef, c.status) FROM CallAudioFile c")
    List<CallStatusRef> findAllRefsAndStatus();

    /** Posição na fila (FIFO por ordem de descoberta) — só tem sentido pra status='pending';
     * conta quantas linhas pendentes foram descobertas antes desta. */
    @Query("SELECT COUNT(c) FROM CallAudioFile c WHERE c.status = 'pending' AND c.ingestedAt < :ingestedAt")
    long countPendingBefore(@Param("ingestedAt") java.time.LocalDateTime ingestedAt);

    /** Total de chamadas de um agente num período — base do relatório de performance (V39). */
    long countByAgentNameAndCallStarttimeBetween(String agentName, java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** Total por origem — dashboard de Insights conta só 'verint' (Fase 3 do Quality
     * Management, V40); a tela "Meus Envios" do portal do supervisor tem seus próprios
     * agregados, à parte, filtrados por uploadedBy. */
    long countBySource(String source);

    /** Arquivos de um lote de upload, na ordem em que foram enviados — base da tela
     * "Meus Envios" (Fase 3 do Quality Management, V40). */
    List<CallAudioFile> findByUploadBatchIdOrderByIdAsc(java.util.UUID uploadBatchId);

    /** Uploads pendentes de processamento — o serviço asteriskia-insights consulta este
     * endpoint em vez de escanear o diretório (o Java já sabe exatamente quais arquivos
     * foram enviados e por quem, sem precisar de descoberta por regex de nome de arquivo,
     * diferente do fluxo Verint). */
    List<CallAudioFile> findBySourceAndStatus(String source, String status);
}
