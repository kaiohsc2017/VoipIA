package com.asteriskia.domain.callcenter;

import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * CcAgent — Agente do Call Center (Fase 2 do plano de módulo Call Center Omnicanal). Metadado
 * próprio do AsteriskIA; o ramal físico correspondente vive em {@link CcExtension} e nas tabelas
 * ARA (ps_endpoints/ps_auths/ps_aors, provisionadas por {@link CallCenterAgentService}).
 */
@Entity
@Table(name = "cc_agents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Integer userId;

    @Column(nullable = false, length = 150)
    private String name;

    // EAGER — mesmo padrão de Ura.businessUnit: o controller serializa direto, sem @Transactional.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Builder.Default
    private Boolean active = true;

    @OneToOne(mappedBy = "agent", fetch = FetchType.EAGER)
    private CcExtension extension;

    /** Toggle de co-browsing gravado do chat (Fase 17, D17-14) — por agente, default false.
     * Se true, ao assumir um chat uma {@code CcCobrowseSession} é criada automaticamente
     * (sujeita ao consentimento do cliente — ver {@code CobrowseConsentService}). */
    @Builder.Default
    @Column(name = "cobrowse_enabled", nullable = false)
    private Boolean cobrowseEnabled = false;

    /** Blending de chat (Fase 7c) — limite de chats simultâneos deste agente. Nulo OU zero =
     * "sem valor próprio", a regra que vale é a da fila ({@code CcQueue.maxConcurrentChats});
     * qualquer valor {@code > 0} aqui sempre prevalece sobre o da fila. Ver
     * {@code ChatBlendingService.resolveLimit}. */
    @Column(name = "max_concurrent_chats")
    private Integer maxConcurrentChats;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
