package com.asteriskia.domain.callcenter.kb;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CcKbArticleRepository extends JpaRepository<CcKbArticle, Long> {

    List<CcKbArticle> findAllByOrderByTitleAsc();

    /** Consumido por {@code CallCenterKbIndexingScheduler} — artigos cuja versão atual ainda não
     * foi indexada (edição nova ou artigo recém-criado, indexedVersion=0 por padrão). */
    @Query("SELECT a FROM CcKbArticle a WHERE a.version <> a.indexedVersion")
    List<CcKbArticle> findPendingIndexing();
}
