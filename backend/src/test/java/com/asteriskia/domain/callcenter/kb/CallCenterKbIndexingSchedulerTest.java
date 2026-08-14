package com.asteriskia.domain.callcenter.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterKbIndexingSchedulerTest — cobre os 3 cenários centrais da reindexação de artigos:
 * artigo ativo com edição pendente (reindexa e avança indexedVersion), artigo desativado (só
 * apaga os chunks, sem gastar uma chamada de embedding) e falha de reindexação (indexedVersion
 * NÃO avança, para a próxima passada tentar de novo).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterKbIndexingSchedulerTest {

    @Mock private CcKbArticleRepository articleRepository;
    @Mock private CcKbExternalSourceRepository sourceRepository;
    @Mock private CallCenterKbIndexingService indexingService;
    @Mock private CallCenterKbFetchService fetchService;
    @Mock private CallCenterKbChunkDao chunkDao;

    private CallCenterKbIndexingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler =
                new CallCenterKbIndexingScheduler(
                        articleRepository, sourceRepository, indexingService, fetchService, chunkDao);
    }

    @Test
    @DisplayName("artigo ativo com versão pendente: reindexa e avança indexedVersion")
    void reindexArticles_ativoComVersaoPendente_reindexaEAvancaVersao() {
        var article = CcKbArticle.builder().id(1L).title("A").body("corpo").active(true).version(3).indexedVersion(2).build();
        when(articleRepository.findPendingIndexing()).thenReturn(List.of(article));
        when(indexingService.reindexArticle(1L, "corpo")).thenReturn(true);

        scheduler.reindexArticles();

        assertThat(article.getIndexedVersion()).isEqualTo(3);
        verify(articleRepository).save(article);
        verify(chunkDao, never()).deleteByArticleId(any());
    }

    @Test
    @DisplayName("artigo desativado: só apaga os chunks, sem chamar o serviço de embedding")
    void reindexArticles_desativado_soApagaChunks() {
        var article = CcKbArticle.builder().id(2L).title("B").body("corpo").active(false).version(2).indexedVersion(1).build();
        when(articleRepository.findPendingIndexing()).thenReturn(List.of(article));

        scheduler.reindexArticles();

        verify(chunkDao).deleteByArticleId(2L);
        verify(indexingService, never()).reindexArticle(anyLong(), any());
        assertThat(article.getIndexedVersion()).isEqualTo(2);
        verify(articleRepository).save(article);
    }

    @Test
    @DisplayName("falha na reindexação: indexedVersion não avança, artigo continua pendente")
    void reindexArticles_falha_naoAvancaIndexedVersion() {
        var article = CcKbArticle.builder().id(3L).title("C").body("corpo").active(true).version(2).indexedVersion(1).build();
        when(articleRepository.findPendingIndexing()).thenReturn(List.of(article));
        when(indexingService.reindexArticle(3L, "corpo")).thenReturn(false);

        scheduler.reindexArticles();

        assertThat(article.getIndexedVersion()).isEqualTo(1);
        verify(articleRepository, never()).save(any());
    }

    @Test
    @DisplayName("fetch com sucesso: reindexa a fonte e persiste o resultado")
    void fetchExternalSources_sucesso_reindexaEPersiste() {
        var source = CcKbExternalSource.builder().id(5L).url("https://x.com").active(true).build();
        when(sourceRepository.findByActiveTrue()).thenReturn(List.of(source));
        when(fetchService.fetch("https://x.com")).thenReturn(CallCenterKbFetchService.FetchResult.ok("texto"));

        scheduler.fetchExternalSources();

        verify(indexingService).reindexSource(eq(5L), eq("texto"));
        assertThat(source.getLastFetchSuccess()).isTrue();
        verify(sourceRepository).save(source);
    }

    @Test
    @DisplayName("fetch com falha: nunca chama reindexSource, índice anterior preservado")
    void fetchExternalSources_falha_naoReindexaENaoApagaIndiceAnterior() {
        var source = CcKbExternalSource.builder().id(6L).url("https://y.com").active(true).build();
        when(sourceRepository.findByActiveTrue()).thenReturn(List.of(source));
        when(fetchService.fetch("https://y.com")).thenReturn(CallCenterKbFetchService.FetchResult.failed("timeout"));

        scheduler.fetchExternalSources();

        verify(indexingService, never()).reindexSource(any(), any());
        assertThat(source.getLastFetchSuccess()).isFalse();
        verify(sourceRepository, times(1)).save(source);
    }
}
