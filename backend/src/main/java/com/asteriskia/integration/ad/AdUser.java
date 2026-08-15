package com.asteriskia.integration.ad;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * AdUser — espelho local dos atributos consultáveis de um usuário do Active Directory.
 *
 * <p>Nunca é escrito a partir do login (o bind de autenticação não atualiza estes campos) — só o
 * {@link AdSyncScheduler} grava/atualiza esta tabela. As telas (screen pop do agente, consulta de
 * usuário) leem sempre daqui, nunca do AD ao vivo — resiliente a AD fora do ar (decisão D4 do plano
 * do módulo Call Center).
 */
@Entity
@Table(name = "ad_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sam_account_name", nullable = false, unique = true, length = 128)
    private String samAccountName;

    @Column(name = "display_name")
    private String displayName;

    private String department;

    private String office;

    private String title;

    /** Lista de grupos do AD, separada por ";" — texto simples, sem modelo relacional próprio. */
    @Column(name = "member_of", columnDefinition = "text")
    private String memberOf;

    @Column(name = "manager_sam", length = 128)
    private String managerSam;

    private String email;

    @Column(name = "telephone_number", length = 64)
    private String telephoneNumber;

    /** Matrícula do AD (atributo employeeID) — correlaciona cc_agents à identidade AD (Fase 14). */
    @Column(name = "employee_id", length = 64)
    private String employeeId;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
