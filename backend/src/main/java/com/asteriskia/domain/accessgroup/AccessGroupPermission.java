package com.asteriskia.domain.accessgroup;

import jakarta.persistence.*;
import lombok.*;

/**
 * AccessGroupPermission — uma linha da matriz de permissões: para um grupo e
 * um resource_key (menu), define leitura/escrita. O catálogo de resource_key
 * válidos fica em código (não em tabela) — ver {@link ResourceCatalog}.
 */
@Entity
@Table(name = "access_group_permissions")
@IdClass(AccessGroupPermissionId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccessGroupPermission {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private AccessGroup group;

    @Id
    @Column(name = "resource_key", nullable = false, length = 64)
    private String resourceKey;

    @Column(name = "can_read", nullable = false)
    @Builder.Default
    private Boolean canRead = false;

    @Column(name = "can_write", nullable = false)
    @Builder.Default
    private Boolean canWrite = false;
}
