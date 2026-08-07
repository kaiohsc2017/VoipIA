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

/** PsAor — mapeamento direto da tabela ARA {@code ps_aors} (V46). */
@Entity
@Table(name = "ps_aors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PsAor {

    @Id private String id;

    private String contact;

    @Column(name = "max_contacts")
    private Integer maxContacts;

    @Column(name = "qualify_frequency")
    private Integer qualifyFrequency;

    @Column(name = "remove_existing")
    private String removeExisting;
}
