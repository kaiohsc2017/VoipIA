package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * CallTransferEvent — um evento de transferência extraído do XML Verint (par
 * Begin_Call -> Transferred), 0..N por chamada. target_switch_call_id é a
 * chave de correlação com outra gravação já ingerida (ver
 * TransferResolutionService); resolved_at nulo significa que a perna de
 * destino ainda não foi encontrada — estado normal, não erro (ver
 * investigação no plano insights-chamadas-campos-xml).
 */
@Entity
@Table(name = "call_transfer_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallTransferEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audio_file_id", nullable = false)
    private Long audioFileId;

    @Column(name = "transfer_order", nullable = false)
    private Short transferOrder;

    @Column(name = "transferred_at")
    private LocalDateTime transferredAt;

    @Column(name = "disconnected_by", length = 20)
    private String disconnectedBy;

    @Column(name = "target_switch_call_id", length = 50)
    private String targetSwitchCallId;

    @Column(name = "target_extension", length = 20)
    private String targetExtension;

    @Column(name = "target_agent_name", length = 100)
    private String targetAgentName;

    @Column(name = "target_audio_file_id")
    private Long targetAudioFileId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
