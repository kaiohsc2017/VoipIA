package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcInteraction — uma linha por atendimento de voz (chave da futura timeline de omnicanalidade,
 * Fase 9). Criada pelo {@link CallCenterAmiEventListener} ao entrar na fila; ganha
 * {@code answeredAt}/agente quando conectada; fechada com {@code endedAt} e tabulação ao
 * encerrar. Vínculo com {@code cc_recordings} (Fase 3) é feito à parte (coluna
 * {@code interaction_id}, preenchida quando a gravação correspondente for ingerida).
 */
@Entity
@Table(name = "cc_interactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "queue_id")
    private CcQueue queue;

    /** INBOUND (fila, {@code queue} sempre preenchido) ou OUTBOUND (ativo manual do agente,
     * {@code queue} sempre nulo — Fase 23). */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private Direction direction;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id")
    private CcAgent agent;

    @Column(name = "channel_uniqueid", nullable = false, unique = true, length = 64)
    private String channelUniqueId;

    @Column(length = 30)
    private String ani;

    /** Fase 14 — {@code sam_account_name} do AD resolvido pela cascata de identificação
     * ({@code CallCenterIdentityResolver}), ou {@code null} se não resolvido (estado normal). */
    @Column(name = "resolved_ad_sam", length = 128)
    private String resolvedAdSam;

    /** Espelha {@code IdentitySource} — {@code null} junto com {@code resolvedAdSam} nulo. */
    @Column(name = "identity_source", length = 20)
    private String identitySource;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** Posição na fila no instante do {@code QueueCallerJoin} — dado de relatório (Fase 15.1,
     * D11-B); a posição/espera exibidas ao vivo no painel de supervisão vêm do AMI
     * {@code QueueStatus} ({@link com.asteriskia.domain.callcenter.supervision.AmiQueueStatusClient}),
     * não desta coluna. */
    @Column(name = "position_on_join")
    private Integer positionOnJoin;

    /** Nome do canal Asterisk (ex: {@code PJSIP/tronco-0000001a}) no instante do join — necessário
     * para {@code Redirect} via AMI (Fase 15.3), que exige o nome, não o {@code Uniqueid}. */
    @Column(name = "channel_name", length = 80)
    private String channelName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "disposition_id")
    private CcDisposition disposition;

    /** Nota desnormalizada da pesquisa de satisfação desta interação (Fase 21) — nula se não
     * pesquisada ou pesquisa sem nenhuma resposta com nota ainda (ex.: só FALADA_IA pendente). */
    @Column(name = "nps_score")
    private BigDecimal npsScore;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
