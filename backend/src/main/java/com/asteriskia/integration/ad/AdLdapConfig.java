package com.asteriskia.integration.ad;

/**
 * AdLdapConfig — parâmetros de conexão LDAP, lidos via {@code ConfigService} (mesmo padrão de
 * Jira/Zabbix: tela de Settings grava no `.env`, leitura com fallback banco→env→default).
 */
public record AdLdapConfig(
        boolean enabled,
        String host,
        int port,
        boolean useSsl,
        String baseDn,
        String bindDn,
        String bindPassword,
        boolean localFallbackEnabled,
        int defaultAccessGroupId) {

    public String url() {
        return (useSsl ? "ldaps://" : "ldap://") + host + ":" + port;
    }
}
