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
        Boolean active,
        /** Fase 7d — nulo usa o default (2GB/10 dias) na criação; na atualização, nulo mantém o
         * default do banco só se a coluna já não tiver outro valor — por isso o service sempre
         * aplica um valor explícito (nunca deixa nulo ir para o UPDATE). */
        Long attachmentQuotaBytes,
        Integer attachmentRetentionDays) {}
