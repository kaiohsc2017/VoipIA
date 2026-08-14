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

/** CcChatAttachment — um arquivo enviado numa {@link CcChatSession} (Fase 7d), por agente ou
 * cliente. Referencia {@code session_id} (tabela normal), NUNCA {@code cc_chat_messages}, que é
 * particionada (V71) — ver nota na migration V78. {@code storedRelativePath} é relativo à raiz de
 * anexos do canal ({@code {username}/{uuid}_{nome-sanitizado}.ext}), nunca absoluto. */
@Entity
@Table(name = "cc_chat_attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "sender_type", nullable = false, length = 10)
    private String senderType;

    @Column(name = "sender_name", length = 150)
    private String senderName;

    /** Identidade estável para cota/diretório — username do agente autenticado, ou
     * {@code "cliente-<customerRef sanitizado>"} para o cliente. Nunca o nome de exibição. */
    @Column(name = "uploader_key", nullable = false, length = 150)
    private String uploaderKey;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "stored_relative_path", nullable = false, length = 500)
    private String storedRelativePath;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
