package com.asteriskia.domain.callcenter.kb;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterKbIndexingScheduler — reindexa artigos com edição pendente e busca/indexa fontes
 * externas por URL (Fase 25). Nenhuma indexação acontece no hot-path do chat nem no request de
 * escrita da UI — sempre assíncrona, aqui.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterKbIndexingScheduler {

    private final CcKbArticleRepository articleRepository;
    private final CcKbExternalSourceRepository sourceRepository;
    private final CallCenterKbIndexingService indexingService;
    private final CallCenterKbFetchService fetchService;
    private final CallCenterKbChunkDao chunkDao;

    @Scheduled(cron = "${app.callcenter.kb.reindex-articles-cron}")
    public void reindexArticles() {
        var pending = articleRepository.findPendingIndexing();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Reindexação de artigos da base de conhecimento: {} pendente(s).", pending.size());
        for (var article : pending) {
            // Artigo desativado: só remove os chunks (não faz sentido pagar o custo de
            // chunking/embedding de um conteúdo que não deve mais aparecer em busca nenhuma).
            if (!Boolean.TRUE.equals(article.getActive())) {
                chunkDao.deleteByArticleId(article.getId());
                article.setIndexedVersion(article.getVersion());
                articleRepository.save(article);
                continue;
            }
            if (indexingService.reindexArticle(article.getId(), article.getBody())) {
                article.setIndexedVersion(article.getVersion());
                articleRepository.save(article);
            }
            // Falha: indexedVersion não avança — a próxima passada do scheduler tenta de novo.
        }
    }

    @Scheduled(cron = "${app.callcenter.kb.fetch-sources-cron}")
    public void fetchExternalSources() {
        var sources = sourceRepository.findByActiveTrue();
        for (var source : sources) {
            var result = fetchService.fetch(source.getUrl());
            source.setLastFetchedAt(LocalDateTime.now());
            source.setLastFetchSuccess(result.success());
            source.setLastFetchError(result.error());
            if (result.success()) {
                // Falha de indexação (embedding fora do ar) aqui não sobrescreve
                // lastFetchSuccess=true acima — a busca em si funcionou; o índice anterior desta
                // fonte, se houver, permanece intacto (CallCenterKbIndexingService só apaga os
                // chunks antigos depois de gerar todos os embeddings novos com sucesso).
                indexingService.reindexSource(source.getId(), result.text());
            }
            sourceRepository.save(source);
        }
    }
}
