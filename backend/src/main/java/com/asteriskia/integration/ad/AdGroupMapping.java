package com.asteriskia.integration.ad;

import com.asteriskia.domain.accessgroup.AccessGroup;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/** AdGroupMapping — mapeia um grupo do AD (nome/CN) a um grupo de acesso local, opcional. */
@Entity
@Table(name = "ad_group_mappings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdGroupMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ad_group_name", nullable = false, unique = true)
    private String adGroupName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_group_id", nullable = false)
    private AccessGroup accessGroup;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
