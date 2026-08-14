package com.asteriskia.domain.callcenter.kb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcKbExternalSource — fonte externa por URL (Fase 25, D22b — opção A escolhida). Buscada e
 * indexada periodicamente por {@code CallCenterKbIndexingScheduler}, nunca ao vivo no hot-path do
 * chat. Falha de busca nunca invalida os chunks já indexados — só {@code lastFetchSuccess}/
 * {@code lastFetchError} registram o resultado da última tentativa.
 */
@Entity
@Table(name = "cc_kb_external_sources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcKbExternalSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String url;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "last_fetched_at")
    private LocalDateTime lastFetchedAt;

    @Column(name = "last_fetch_success")
    private Boolean lastFetchSuccess;

    @Column(name = "last_fetch_error", length = 300)
    private String lastFetchError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
