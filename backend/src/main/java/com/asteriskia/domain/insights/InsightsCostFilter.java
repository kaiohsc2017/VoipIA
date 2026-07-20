package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/** InsightsCostFilter — filtros opcionais da aba "Custos IA" de Insights. Mirror de
 * CallRecordFilter, reduzido aos campos relevantes (sem URA — Insights não tem). */
public record InsightsCostFilter(
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        String agentName,
        // source/uploadedBy: Fase 3 do Quality Management (V40) — a aba "Custos IA" do
        // Insights usa source="verint" sem uploadedBy; as sub-abas de custo do portal do
        // supervisor ("Meus Envios") usam source="upload" + uploadedBy=principal quando
        // não-ADMIN (ADMIN vê todos os uploads, uploadedBy=null).
        String source,
        String uploadedBy
) {}
