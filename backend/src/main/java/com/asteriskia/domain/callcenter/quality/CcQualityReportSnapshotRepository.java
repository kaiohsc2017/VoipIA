package com.asteriskia.domain.callcenter.quality;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistência dos pontos de evolução (Fase 26) — a comparação em si é feita a partir do
 * {@code content_json} do relatório anterior ({@link CcQualityReportService#buildEvolution}),
 * não a partir desta tabela; as linhas aqui existem para uma futura tela de série histórica
 * (mais de 2 pontos), ainda não pedida. */
@Repository
public interface CcQualityReportSnapshotRepository extends JpaRepository<CcQualityReportSnapshot, Long> {}
