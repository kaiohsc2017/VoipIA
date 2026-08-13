package com.asteriskia.domain.callcenter.supervision;

/**
 * WaitingCallerView — um chamador em espera numa fila, obtido ao vivo via AMI {@code QueueStatus}
 * (Fase 15.1). {@code channelName} é o nome real do canal Asterisk no instante da consulta —
 * necessário para o {@code Redirect} (Fase 15.3), que exige nome, não {@code Uniqueid}.
 */
public record WaitingCallerView(
        Integer position, String ani, Long waitSeconds, String channelUniqueId, String channelName) {}
