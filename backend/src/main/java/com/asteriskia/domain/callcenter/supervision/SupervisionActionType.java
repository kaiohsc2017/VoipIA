package com.asteriskia.domain.callcenter.supervision;

/** SupervisionActionType — ação de supervisão registrada em auditoria (Fase 6). */
public enum SupervisionActionType {
    LISTEN,
    WHISPER,
    BARGE,
    FORCE_PAUSE,
    FORCE_UNPAUSE,
    /** Retirar chamada da fila e redirecionar para outra fila (Fase 15.3). */
    REDIRECT_QUEUE,
    /** Retirar chamada da fila e redirecionar direto para um agente (Fase 15.3). */
    REDIRECT_AGENT
}
