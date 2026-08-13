package com.asteriskia.domain.callcenter.chat;

/** ChatChannelView — DTO de leitura de {@link CcChatChannel} (Fase 24). */
public record ChatChannelView(
        Long id,
        String code,
        String displayName,
        String type,
        Long defaultQueueId,
        String defaultQueueName,
        Long botFlowId,
        String botFlowName,
        String greetingMessage,
        String awayMessage,
        Boolean active) {

    public static ChatChannelView from(CcChatChannel channel) {
        return new ChatChannelView(
                channel.getId(),
                channel.getCode(),
                channel.getDisplayName(),
                channel.getType(),
                channel.getDefaultQueue() != null ? channel.getDefaultQueue().getId() : null,
                channel.getDefaultQueue() != null ? channel.getDefaultQueue().getDisplayName() : null,
                channel.getBotFlow() != null ? channel.getBotFlow().getId() : null,
                channel.getBotFlow() != null ? channel.getBotFlow().getName() : null,
                channel.getGreetingMessage(),
                channel.getAwayMessage(),
                channel.getActive());
    }
}
