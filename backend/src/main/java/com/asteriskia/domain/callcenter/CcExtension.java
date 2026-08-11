package com.asteriskia.domain.callcenter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcExtension — metadado do ramal ARA provisionado para um {@link CcAgent} (1:1). A senha
 * ({@link #secret}) nunca é serializada em JSON ({@code @JsonIgnore}) — só é exposta pelo
 * endpoint dedicado {@code GET /api/v1/callcenter/agentes/{id}/ramal-secret}, protegido pelo
 * resource_key granular {@code callcenter.ramais} (mesmo padrão já usado pela senha de ramal SIP
 * do RBAC granular original).
 */
@Entity
@Table(name = "cc_extensions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // JsonIgnore evita recursão infinita na serialização (CcAgent.extension -> CcExtension.agent
    // -> CcAgent.extension -> ...); quem serializa a partir de CcAgent já tem o agente.
    @JsonIgnore
    @OneToOne
    @JoinColumn(name = "agent_id", nullable = false, unique = true)
    private CcAgent agent;

    @Column(nullable = false, unique = true, length = 20)
    private String extension;

    @JsonIgnore
    @Column(nullable = false, length = 64)
    private String secret;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
