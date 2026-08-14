package com.asteriskia.domain.callcenter.kb;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterKbChunkDao — acesso a {@code cc_kb_chunks} (Fase 25). Usa {@link JdbcTemplate} com
 * SQL nativo em vez de mapear a coluna {@code embedding vector(384)} como entidade JPA: o
 * pgvector aceita o literal de texto {@code '[0.1,0.2,...]'} com cast {@code ::vector} numa
 * query parametrizada normal — dispensa um tipo Hibernate customizado só para esta coluna
 * (YAGNI, é a única tabela do projeto com uma coluna vetorial).
 */
@Repository
@RequiredArgsConstructor
public class CallCenterKbChunkDao {

    private final JdbcTemplate jdbcTemplate;

    /** Reindexação nunca faz UPDATE incremental — apaga e recria todos os chunks do dono (mesma
     * disciplina documentada na migration V69). */
    public void deleteByArticleId(Long articleId) {
        jdbcTemplate.update("DELETE FROM cc_kb_chunks WHERE article_id = ?", articleId);
    }

    public void deleteBySourceId(Long sourceId) {
        jdbcTemplate.update("DELETE FROM cc_kb_chunks WHERE source_id = ?", sourceId);
    }

    public void insertArticleChunk(Long articleId, int chunkIndex, String chunkText, String vectorLiteral) {
        jdbcTemplate.update(
                "INSERT INTO cc_kb_chunks (article_id, chunk_index, chunk_text, embedding) "
                        + "VALUES (?, ?, ?, ?::vector)",
                articleId, chunkIndex, chunkText, vectorLiteral);
    }

    public void insertSourceChunk(Long sourceId, int chunkIndex, String chunkText, String vectorLiteral) {
        jdbcTemplate.update(
                "INSERT INTO cc_kb_chunks (source_id, chunk_index, chunk_text, embedding) "
                        + "VALUES (?, ?, ?, ?::vector)",
                sourceId, chunkIndex, chunkText, vectorLiteral);
    }

    /** Apaga e recria os chunks de um artigo numa única transação (achado de revisão: sem isso,
     * uma falha de banco no meio do laço de inserts deixava o dono com o índice antigo já apagado
     * e só parte dos chunks novos gravados — estado inconsistente sem retry nenhum cobrindo o
     * caso). Chamado depois que TODOS os embeddings novos já foram gerados com sucesso
     * ({@link CallCenterKbIndexingService}) — esta transação é só de banco, nunca envolve a
     * chamada HTTP ao servidor de embeddings. */
    @Transactional
    public void replaceArticleChunks(Long articleId, List<String> texts, List<String> vectorLiterals) {
        deleteByArticleId(articleId);
        for (int i = 0; i < texts.size(); i++) {
            insertArticleChunk(articleId, i, texts.get(i), vectorLiterals.get(i));
        }
    }

    @Transactional
    public void replaceSourceChunks(Long sourceId, List<String> texts, List<String> vectorLiterals) {
        deleteBySourceId(sourceId);
        for (int i = 0; i < texts.size(); i++) {
            insertSourceChunk(sourceId, i, texts.get(i), vectorLiterals.get(i));
        }
    }

    /** Busca os {@code k} trechos mais próximos (distância de cosseno, {@code <=>}) entre
     * artigos/fontes ativos — nunca retorna chunk de um dono desativado, mesmo que ainda não
     * tenha sido fisicamente removido. */
    public List<ChunkMatch> searchTopK(String queryVectorLiteral, int k) {
        return jdbcTemplate.query(
                """
                SELECT c.chunk_text,
                       1 - (c.embedding <=> ?::vector) AS similarity,
                       a.title AS article_title,
                       s.url AS source_url
                FROM cc_kb_chunks c
                LEFT JOIN cc_kb_articles a ON a.id = c.article_id AND a.active = TRUE
                LEFT JOIN cc_kb_external_sources s ON s.id = c.source_id AND s.active = TRUE
                WHERE (c.article_id IS NOT NULL AND a.id IS NOT NULL)
                   OR (c.source_id IS NOT NULL AND s.id IS NOT NULL)
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, rowNum) ->
                        new ChunkMatch(
                                rs.getString("chunk_text"),
                                rs.getDouble("similarity"),
                                rs.getString("article_title"),
                                rs.getString("source_url")),
                queryVectorLiteral, queryVectorLiteral, k);
    }

    /** Um trecho recuperado, com sua citação de origem (título do artigo OU URL da fonte —
     * nunca os dois, mesmo CHECK constraint de {@code cc_kb_chunks}). */
    public record ChunkMatch(String chunkText, double similarity, String articleTitle, String sourceUrl) {
        public String citation() {
            return articleTitle != null ? articleTitle : sourceUrl;
        }
    }
}
