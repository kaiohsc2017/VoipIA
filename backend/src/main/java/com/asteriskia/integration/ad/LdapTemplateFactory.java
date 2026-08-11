package com.asteriskia.integration.ad;

import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;

/**
 * LdapTemplateFactory — isola a construção do {@link LdapTemplate} num ponto único e mockável
 * (a conexão real com o AD não pode ser exercitada em teste unitário — {@code LdapClientTest} mocka
 * esta interface, não o LdapTemplate final).
 */
public interface LdapTemplateFactory {
    LdapTemplate create(AdLdapConfig config);

    @Component
    class Default implements LdapTemplateFactory {
        @Override
        public LdapTemplate create(AdLdapConfig config) {
            LdapContextSource contextSource = new LdapContextSource();
            contextSource.setUrl(config.url());
            contextSource.setBase(config.baseDn());
            contextSource.setUserDn(config.bindDn());
            contextSource.setPassword(config.bindPassword());
            // Timeout curto — login não pode travar esperando um DC fora do ar (item de risco
            // explícito do plano do módulo Call Center).
            contextSource.setBaseEnvironmentProperties(
                    java.util.Map.of(
                            "com.sun.jndi.ldap.connect.timeout", "3000",
                            "com.sun.jndi.ldap.read.timeout", "5000"));
            contextSource.afterPropertiesSet();
            return new LdapTemplate(contextSource);
        }
    }
}
