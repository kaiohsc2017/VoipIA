package com.asteriskia.domain.callcenter.interaction;

/**
 * AgentState — estados possíveis do agente do Call Center (Fase 4). DISPONIVEL/PAUSA/OFFLINE são
 * transições manuais do próprio agente; EM_ATENDIMENTO e ACW são disparados automaticamente pelo
 * {@link CallCenterAmiEventListener} ao receber eventos AMI de conexão/encerramento de chamada.
 */
public enum AgentState {
    DISPONIVEL,
    EM_ATENDIMENTO,
    ACW,
    PAUSA,
    OFFLINE
}
