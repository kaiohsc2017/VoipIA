package com.asteriskia.domain.callcenter.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcTelegramPollState — retomada do long polling do Telegram após restart, sem reprocessar updates
 * antigos (Fase 7e, D2). Uma linha por canal Telegram (PK = {@code channel_id}) — sem FK formal
 * de propósito, para não travar a exclusão/recriação de um canal por causa deste estado auxiliar
 * de polling; a referência é mantida pela aplicação ({@link TelegramLongPollingClient}).
 */
@Entity
@Table(name = "cc_telegram_poll_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcTelegramPollState {

    @Id
    @Column(name = "channel_id")
    private Long channelId;

    @Builder.Default
    @Column(name = "last_update_id", nullable = false)
    private Long lastUpdateId = 0L;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
