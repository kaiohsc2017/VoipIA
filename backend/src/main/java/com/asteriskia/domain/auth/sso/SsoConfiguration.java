package com.asteriskia.domain.auth.sso;

import com.asteriskia.domain.accessgroup.AccessGroup;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sso_configurations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SsoConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_name", nullable = false, length = 50)
    @Builder.Default
    private String providerName = "MICROSOFT_ENTRA";

    @Column(name = "display_name", nullable = false, length = 100)
    @Builder.Default
    private String displayName = "Microsoft 365 / Entra ID";

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "client_secret")
    private String clientSecret;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "discovery_url", length = 500)
    private String discoveryUrl;

    @Column(name = "authorization_url", length = 500)
    private String authorizationUrl;

    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "user_info_url", length = 500)
    private String userInfoUrl;

    @Column(name = "redirect_uri", length = 500)
    private String redirectUri;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_access_group_id")
    private AccessGroup defaultAccessGroup;

    @Column(name = "auto_provision_users", nullable = false)
    @Builder.Default
    private Boolean autoProvisionUsers = true;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
