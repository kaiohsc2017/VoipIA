package com.asteriskia.domain.callcenter.ara;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AraQueue — mapeamento direto da tabela ARA {@code queues} (V46), lida nativamente pelo
 * app_queue. Nome da classe evita colisão com {@link com.asteriskia.domain.callcenter.CcQueue}
 * (nosso metadado — BU, skills, auditoria).
 */
@Entity
@Table(name = "queues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AraQueue {

    @Id private String name;

    private String context;
    private String strategy;
    private Integer timeout;
    private Integer maxlen;
    private String musiconhold;
    private Integer wrapuptime;
}
