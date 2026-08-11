package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/** Lançada quando um supervisor tenta gerar um relatório do mesmo atendente antes do
 * cooldown de 5 dias úteis terminar (Fase 2 do Quality Management, V39) — ADMIN é
 * isento desta checagem. Mapeada para HTTP 429 pelo AgentReportController. */
public class ReportCooldownException extends RuntimeException {

    private final LocalDateTime nextAllowedAt;

    public ReportCooldownException(LocalDateTime nextAllowedAt) {
        super("Cooldown de relatório ativo até " + nextAllowedAt);
        this.nextAllowedAt = nextAllowedAt;
    }

    public LocalDateTime getNextAllowedAt() {
        return nextAllowedAt;
    }
}
