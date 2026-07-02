package com.asteriskia.domain.accessgroup;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AccessGroup — grupo de acesso do RBAC granular (V22). Cada usuário pertence
 * a exatamente um grupo; a matriz de permissões (leitura/escrita por menu)
 * fica em {@link AccessGroupPermission}. "Administradores" e "Usuários" são
 * os grupos de sistema (isSystem=true) seedados na migration — reproduzem o
 * comportamento binário ADMIN|USER anterior e não podem ser excluídos.
 */
@Entity
@Table(name = "access_groups")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccessGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private Boolean isSystem = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
