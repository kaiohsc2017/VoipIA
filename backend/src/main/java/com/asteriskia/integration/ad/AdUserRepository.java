package com.asteriskia.integration.ad;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdUserRepository extends JpaRepository<AdUser, Long> {
    Optional<AdUser> findBySamAccountName(String samAccountName);

    /** Login digitado/falado pode chegar com caixa diferente do espelhado pelo sync — busca de
     * identificação (Fase 14) nunca deve depender de exatidão de maiúsculas/minúsculas. */
    Optional<AdUser> findBySamAccountNameIgnoreCase(String samAccountName);

    /** ANI/telefone informado já deve chegar normalizado (só dígitos) pelo chamador — comparação
     * exata contra o valor espelhado do AD, também normalizado pelo sync. */
    Optional<AdUser> findByTelephoneNumber(String telephoneNumber);

    /** Top candidato por similaridade trigram de nome falado contra {@code display_name} (Fase
     * 14 — confirmação falada obrigatória antes de qualquer screen pop). Projeção com o próprio
     * score: sem confirmação de um limiar mínimo, {@link CallCenterIdentityResolver} nunca usaria
     * o candidato só porque ele "existe" no banco.
     *
     * <p><b>Achado de performance corrigido</b>: o operador {@code %} (similaridade trigram) no
     * {@code WHERE} — e não só {@code similarity(...)} no {@code SELECT}/{@code ORDER BY} — é
     * exigido para o índice GIN de trigram (migration V85, {@code idx_ad_users_display_name_trgm})
     * ser de fato usado; sem ele, toda resolução de identidade por voz fazia sequential scan
     * completo em {@code ad_users}. O limiar de similaridade do operador {@code %} é controlado
     * por {@code pg_trgm.similarity_threshold} (padrão 0.3) — mesmo comportamento funcional de
     * antes, só a forma da query mudou. */
    @Query(
            value =
                    "SELECT id, similarity(display_name, :spokenName) AS score "
                            + "FROM ad_users WHERE display_name % :spokenName "
                            + "ORDER BY score DESC LIMIT 1",
            nativeQuery = true)
    Optional<AdUserMatchProjection> findBestFuzzyMatchByDisplayName(@Param("spokenName") String spokenName);

    interface AdUserMatchProjection {
        Long getId();

        Double getScore();
    }

    List<AdUser> findAll();

    /** Busca em lote para a sincronização batch (achado de auditoria 2026-08-20 — evita 1
     * SELECT por usuário dentro do laço de {@code AdSyncScheduler}). */
    List<AdUser> findBySamAccountNameIn(Collection<String> samAccountNames);
}
