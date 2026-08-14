package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CustomerProfileSummaryRow — uma linha da listagem de clientes do "Perfil do cliente" (Fase
 * 27). {@code normalizedId} é a chave de identidade (ver {@link AniNormalizer}); GAP CONHECIDO:
 * sem {@code resolved_ad_sam} (Fase 14, inexistente), é o único identificador disponível — dois
 * clientes podem ficar separados se o telefone informado no chat não normalizar para o mesmo
 * dígito da ligação de voz.
 */
public record CustomerProfileSummaryRow(
        String normalizedId,
        String displayContact,
        int totalChamadas,
        int totalChats,
        LocalDateTime primeiroContato,
        LocalDateTime ultimoContato,
        BigDecimal npsMedio,
        String topAssunto) {
}
