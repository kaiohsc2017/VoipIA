package com.asteriskia.domain.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AppUser — Usuário do sistema AsteriskIA.
 * Cada usuário possui um ramal SIP WebRTC único (a partir de 9001).
 */
@Entity
@Table(name = "app_users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** Hash BCrypt da senha */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** Ramal SIP WebRTC (9001, 9002, ...) */
    @Column(nullable = false, unique = true)
    private Integer extension;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * ADMIN | USER — legado, mantido para compat durante a transição pro
     * RBAC granular (dual-emit no JWT). Ver {@link com.asteriskia.domain.accessgroup.AccessGroup}.
     */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String role = "USER";

    /** Grupo de acesso (RBAC granular) — substitui role a partir da V22. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_group_id", nullable = false)
    private com.asteriskia.domain.accessgroup.AccessGroup accessGroup;

    /** Segredo TOTP em Base32 (null se 2FA não configurado). */
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    /** Se true, o login exige validação de código TOTP após senha. */
    @Column(name = "totp_enabled", nullable = false)
    @Builder.Default
    private Boolean totpEnabled = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

