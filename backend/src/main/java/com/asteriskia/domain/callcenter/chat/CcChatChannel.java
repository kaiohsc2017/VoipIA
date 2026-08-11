package com.asteriskia.domain.callcenter.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcChatChannel — canal de origem de uma sessão de chat (Fase 7a: só o simulador interno
 * {@code internal_test}; webchat/WhatsApp/Telegram chegam na Fase 7b, junto com o esquema de
 * autenticação anônima do cliente final).
 */
@Entity
@Table(name = "cc_chat_channels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcChatChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
