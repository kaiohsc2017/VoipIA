package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CallTranscriptSegmentRepository extends JpaRepository<CallTranscriptSegment, Long> {

    List<CallTranscriptSegment> findByAudioFileIdOrderByStartMsAsc(Long audioFileId);

    void deleteByAudioFileId(Long audioFileId);

    /** Chamadas com pelo menos um turno do locutor informado no tom informado
     * (cruza tom acústico OU semântico — qualquer um dos dois sinais serve de match). */
    @Query("SELECT DISTINCT s.audioFileId FROM CallTranscriptSegment s " +
           "WHERE s.speaker = :speaker AND (s.toneAcoustic = :tone OR s.toneSemantic = :tone)")
    List<Long> findAudioFileIdsBySpeakerAndTone(@Param("speaker") String speaker, @Param("tone") String tone);

    /** Busca por texto livre (full-text português) — usa a coluna gerada text_search (V35). */
    @Query(value = "SELECT DISTINCT audio_file_id FROM call_transcript_segments " +
                   "WHERE text_search @@ plainto_tsquery('portuguese', :query)", nativeQuery = true)
    List<Long> findAudioFileIdsByTextSearch(@Param("query") String query);

    /** Busca por frase exata (ordem das palavras importa) — mesma coluna, phraseto_tsquery. */
    @Query(value = "SELECT DISTINCT audio_file_id FROM call_transcript_segments " +
                   "WHERE text_search @@ phraseto_tsquery('portuguese', :phrase)", nativeQuery = true)
    List<Long> findAudioFileIdsByPhraseSearch(@Param("phrase") String phrase);
}
