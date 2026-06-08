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

    /** ADMIN | USER */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String role = "USER";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
