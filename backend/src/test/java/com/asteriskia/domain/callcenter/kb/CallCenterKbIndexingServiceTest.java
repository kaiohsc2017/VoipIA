package com.asteriskia.domain.callcenter.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
 * CallCenterKbIndexingServiceTest — cobre a garantia central documentada em 3 lugares do código
 * (esta classe, o scheduler, a migration V69): todos os embeddings novos são gerados ANTES de
 * apagar os chunks antigos — uma falha no meio do caminho nunca deve chamar
 * {@code CallCenterKbChunkDao.replace*Chunks} (índice anterior permanece intacto).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterKbIndexingServiceTest {

    @Mock private CallCenterKbChunkDao chunkDao;
    @Mock private CallCenterKbEmbeddingClient embeddingClient;

    private CallCenterKbIndexingService service;

    @BeforeEach
    void setUp() {
        service = new CallCenterKbIndexingService(chunkDao, embeddingClient);
    }

    @Test
    @DisplayName("artigo: gera embedding de cada chunk e substitui na ordem correta")
    void reindexArticle_sucesso_substituiChunksNaOrdem() {
        when(embeddingClient.embedAsVectorLiteral("Primeiro parágrafo.")).thenReturn("[0.1]");
        when(embeddingClient.embedAsVectorLiteral("Segundo parágrafo.")).thenReturn("[0.2]");

        boolean ok = service.reindexArticle(10L, "Primeiro parágrafo.\n\nSegundo parágrafo.");

        assertThat(ok).isTrue();
        verify(chunkDao)
                .replaceArticleChunks(
                        eq(10L),
                        eq(List.of("Primeiro parágrafo.", "Segundo parágrafo.")),
                        eq(List.of("[0.1]", "[0.2]")));
    }

    @Test
    @DisplayName("falha ao gerar embedding no meio do laço: nunca chama replaceArticleChunks — índice anterior intacto")
    void reindexArticle_falhaNoMeioDoLaco_naoSubstituiChunks() {
        when(embeddingClient.embedAsVectorLiteral("Primeiro parágrafo."))
                .thenThrow(new RuntimeException("servidor de embeddings fora do ar"));

        boolean ok = service.reindexArticle(11L, "Primeiro parágrafo.\n\nSegundo parágrafo.");

        assertThat(ok).isFalse();
        verify(chunkDao, never()).replaceArticleChunks(any(), any(), any());
    }

    @Test
    @DisplayName("fonte externa: mesma garantia de reindexArticle, via replaceSourceChunks")
    void reindexSource_sucesso_substituiChunks() {
        when(embeddingClient.embedAsVectorLiteral("Conteúdo da página.")).thenReturn("[0.3]");

        boolean ok = service.reindexSource(20L, "Conteúdo da página.");

        assertThat(ok).isTrue();
        verify(chunkDao).replaceSourceChunks(20L, List.of("Conteúdo da página."), List.of("[0.3]"));
    }

    @Test
    @DisplayName("corpo vazio: nenhum chunk gerado, mas ainda assim substitui (esvazia) os chunks do dono")
    void reindexArticle_corpoVazio_substituiComListaVazia() {
        boolean ok = service.reindexArticle(12L, "   ");

        assertThat(ok).isTrue();
        verify(chunkDao).replaceArticleChunks(12L, List.of(), List.of());
    }
}
