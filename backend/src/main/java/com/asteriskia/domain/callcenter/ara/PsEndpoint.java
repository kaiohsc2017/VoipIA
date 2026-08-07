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
 * PsEndpoint — mapeamento direto da tabela ARA {@code ps_endpoints} (Realtime do res_pjsip,
 * V46). O Asterisk lê estas linhas on-demand via sorcery.conf/extconfig.conf — nenhum reload é
 * necessário após um INSERT/UPDATE/DELETE aqui.
 */
@Entity
@Table(name = "ps_endpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PsEndpoint {

    @Id private String id;

    private String transport;
    private String aors;
    private String auth;
    private String context;
    private String disallow;
    private String allow;

    @Column(name = "direct_media")
    private String directMedia;

    @Column(name = "force_rport")
    private String forceRport;

    @Column(name = "rewrite_contact")
    private String rewriteContact;

    private String callerid;

    @Column(name = "identify_by")
    private String identifyBy;
}
