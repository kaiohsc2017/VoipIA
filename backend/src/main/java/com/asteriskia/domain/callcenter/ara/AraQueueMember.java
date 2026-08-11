package com.asteriskia.domain.callcenter.ara;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** AraQueueMember — mapeamento direto da tabela ARA {@code queue_members} (V46). */
@Entity
@Table(name = "queue_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AraQueueMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long uniqueid;

    @Column(name = "queue_name")
    private String queueName;

    @Column(name = "interface")
    private String interfaceName;

    @Column(name = "membername")
    private String memberName;

    @Column(name = "state_interface")
    private String stateInterface;

    private Integer penalty;
    private Integer paused;
}
