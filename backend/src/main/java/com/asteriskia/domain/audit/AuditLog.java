package com.asteriskia.domain.audit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * AuditLog — Registro de auditoria de ações do sistema (Fase 13).
 *
 * Ações registradas: LOGIN, LOGIN_FAILED, SETTINGS_CHANGE, USER_CREATE,
 * USER_UPDATE, USER_DELETE, EXPORT, RATE_LIMIT_BLOCKED, TOTP_ENABLED,
 * TOTP_DISABLED, TOTP_VERIFY_FAILED.
 */
@Entity
@Table(name = "audit_logs",
       indexes = {
           @Index(name = "idx_audit_created_at", columnList = "created_at DESC"),
           @Index(name = "idx_audit_username",   columnList = "username"),
           @Index(name = "idx_audit_action",     columnList = "action"),
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Username autenticado (null para tentativas inválidas). */
    @Column(length = 64)
    private String username;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Tipo de ação:
     * LOGIN | LOGIN_FAILED | SETTINGS_CHANGE | USER_CREATE | USER_UPDATE |
     * USER_DELETE | EXPORT | RATE_LIMIT_BLOCKED | TOTP_ENABLED |
     * TOTP_DISABLED | TOTP_VERIFY_FAILED
     */
    @Column(nullable = false, length = 64)
    private String action;

    /** Detalhes opcionais (ex: "Chave JIRA_BASE_URL alterada", "Usuário kaio criado"). */
    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    @Builder.Default
    private Boolean success = true;

    @Column(name = "user_agent", length = 512)
    private String userAgent;
}
