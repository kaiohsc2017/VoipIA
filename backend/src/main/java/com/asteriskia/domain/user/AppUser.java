package com.asteriskia.domain.user;

import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * AppUser — Usuário do sistema VoipIA.
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

    /**
     * Unidades de Negócio (BU) do usuário — obrigatório, restringe os dados visíveis a essas BUs.
     * EAGER (não LAZY): businessUnitIds() é chamado em AuthController/TotpController fora de
     * qualquer transação (após o AppUser já ter saído do repositório) — LAZY aqui vira
     * LazyInitializationException a cada login (spring.jpa.open-in-view=false neste projeto).
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_business_units",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "business_unit_id")
    )
    @Builder.Default
    private Set<BusinessUnit> businessUnits = new HashSet<>();

    /** Data limite de acesso (máx. 60 dias a partir da criação/edição). Null se access_indeterminate=true. */
    @Column(name = "access_expires_at")
    private LocalDate accessExpiresAt;

    /** Se true, o usuário tem acesso por tempo indeterminado — accessExpiresAt deve ficar null. */
    @Column(name = "access_indeterminate", nullable = false)
    @Builder.Default
    private Boolean accessIndeterminate = false;

    /** Marca se o usuário já passou pela oferta de configuração de MFA no primeiro login. */
    @Column(name = "first_login_completed", nullable = false)
    @Builder.Default
    private Boolean firstLoginCompleted = false;

    /**
     * True somente para contas provisionadas via bind AD (módulo Call Center, Fase 1). Só uma
     * conta com adLinked=true pode ser autenticada pelo fallback AD do AuthController — impede que
     * uma conta local pré-existente com o mesmo username de um usuário do AD seja sequestrada por
     * quem souber a senha AD daquele username.
     */
    @Column(name = "ad_linked", nullable = false)
    @Builder.Default
    private Boolean adLinked = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** IDs das BUs do usuário — usado para popular a claim "bu" do JWT. */
    public Set<Integer> businessUnitIds() {
        return businessUnits.stream().map(BusinessUnit::getId).collect(java.util.stream.Collectors.toSet());
    }

    /** true se o acesso do usuário expirou (accessExpiresAt no passado e não é indeterminado). */
    public boolean hasExpiredAccess() {
        return !Boolean.TRUE.equals(accessIndeterminate)
                && accessExpiresAt != null
                && accessExpiresAt.isBefore(LocalDate.now());
    }
}

