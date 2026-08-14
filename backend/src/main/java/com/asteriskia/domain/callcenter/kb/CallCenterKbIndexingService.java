package com.asteriskia.domain.callcenter.kb;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * CallCenterKbIndexingService — (re)gera os chunks + embeddings de um artigo ou fonte externa
 * (Fase 25). Reindexação nunca faz UPDATE incremental: apaga todos os chunks do dono e recria do
 * zero — mais simples e sem risco de chunk órfão de uma versão antiga do texto. Todos os
 * embeddings são gerados ANTES de apagar os chunks antigos — uma falha no meio do caminho (ex.:
 * servidor de embeddings fora do ar) deixa o índice anterior intacto, em vez de zerar os chunks
 * do dono e só depois falhar. O apaga+recria em si é uma única transação de banco
 * ({@code CallCenterKbChunkDao.replaceArticleChunks}/{@code replaceSourceChunks}) — nunca
 * envolve a chamada HTTP de embedding, então uma falha de banco no meio do laço de inserts
 * também não deixa um estado parcialmente indexado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterKbIndexingService {

    private final CallCenterKbChunkDao chunkDao;
    private final CallCenterKbEmbeddingClient embeddingClient;

    public boolean reindexArticle(Long articleId, String body) {
        return reindex(body, chunkDao::replaceArticleChunks, articleId, "artigo");
    }

    public boolean reindexSource(Long sourceId, String body) {
        return reindex(body, chunkDao::replaceSourceChunks, sourceId, "fonte externa");
    }

    private boolean reindex(String body, ReplaceChunksFunction replace, Long ownerId, String ownerLabel) {
        var texts = CallCenterKbChunker.chunk(body);
        List<String> vectors = new ArrayList<>(texts.size());
        try {
            for (String text : texts) {
                vectors.add(embeddingClient.embedAsVectorLiteral(text));
            }
        } catch (Exception e) {
            log.warn(
                    "Falha ao gerar embeddings para {} id={} (causa={}) — índice anterior mantido intacto.",
                    ownerLabel, ownerId, e.getClass().getSimpleName());
            return false;
        }
        replace.replace(ownerId, texts, vectors);
        log.info("Reindexação concluída: {} id={} ({} chunk(s))", ownerLabel, ownerId, texts.size());
        return true;
    }

    @FunctionalInterface
    private interface ReplaceChunksFunction {
        void replace(Long ownerId, List<String> texts, List<String> vectorLiterals);
    }
}
