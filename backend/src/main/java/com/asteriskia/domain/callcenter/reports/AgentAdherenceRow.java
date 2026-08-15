package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AgentAdherenceRow — aderência à escala de um agente num dia (sub-fase 9c.7).
 * {@code adherencePct} é {@code null} (nunca 0) quando o agente não tem turno cadastrado para
 * aquele dia da semana — ausência de escala não é "aderência zero", é "não se aplica".
 */
public record AgentAdherenceRow(
        LocalDate date, Long scheduledSeconds, Long loggedSeconds, BigDecimal adherencePct) {
}
