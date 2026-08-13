package com.asteriskia.domain.callcenter.interaction;

/**
 * Direction — sentido de uma {@link CcInteraction} (Fase 23 do plano omnicanal Parte III).
 * INBOUND é o único sentido existente antes desta fase (chamada entra numa fila, {@code queue}
 * sempre preenchido); OUTBOUND é a chamada ativa manual originada pelo próprio ramal do agente
 * (dial direto pelo softphone, sem fila — {@code queue} sempre nulo).
 */
public enum Direction {
    INBOUND,
    OUTBOUND
}
