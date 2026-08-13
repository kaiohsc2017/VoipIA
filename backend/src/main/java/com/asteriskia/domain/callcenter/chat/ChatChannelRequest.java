package com.asteriskia.domain.callcenter.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * ChatChannelRequest — payload de criação/atualização de {@link CcChatChannel} (Fase 24).
 * {@code defaultQueueId} é obrigatório na prática (sem ele o canal responde 503 ao iniciar
 * sessão) mas não é validado como {@code @NotNull} aqui — um canal pode ser cadastrado antes de
 * a fila existir e completado depois, mesmo padrão de tolerância já usado por outras telas de
 * configuração deste módulo (ex.: fila sem pesquisa de NPS associada).
 */
public record ChatChannelRequest(
        @NotBlank String code,
        @NotBlank String displayName,
        String type,
        Long defaultQueueId,
        Long botFlowId,
        String greetingMessage,
        String awayMessage,
        Boolean active) {}
