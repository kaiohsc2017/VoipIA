package com.asteriskia.domain.callcenter.interaction;

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

/** CcDisposition — tabulação/motivo de encerramento de uma {@link CcInteraction} (catálogo). */
@Entity
@Table(name = "cc_dispositions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcDisposition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
