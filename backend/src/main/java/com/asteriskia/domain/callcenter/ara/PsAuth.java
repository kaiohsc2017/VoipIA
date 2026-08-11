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

/** PsAuth — mapeamento direto da tabela ARA {@code ps_auths} (V46). */
@Entity
@Table(name = "ps_auths")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PsAuth {

    @Id private String id;

    @Column(name = "auth_type")
    private String authType;

    private String password;
    private String realm;
    private String username;
}
