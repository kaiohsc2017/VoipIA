package com.asteriskia.integration.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.asteriskia.domain.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ldap.core.LdapTemplate;

@ExtendWith(MockitoExtension.class)
class LdapClientTest {

    @Mock private ConfigService config;
    @Mock private LdapTemplateFactory templateFactory;
    @Mock private LdapTemplate ldapTemplate;

    private LdapClient ldapClient;

    @BeforeEach
    void setUp() {
        ldapClient = new LdapClient(config, templateFactory);
    }

    @Test
    void authenticate_adDesabilitado_naoTentaBind() {
        when(config.get("AD_LDAP_ENABLED", "false")).thenReturn("false");
        when(config.get("AD_LDAP_HOST", "")).thenReturn("dc.empresa.local");
        when(config.getInt("AD_LDAP_PORT", 636)).thenReturn(636);
        when(config.get("AD_LDAP_USE_SSL", "true")).thenReturn("true");
        when(config.get("AD_LDAP_BASE_DN", "")).thenReturn("DC=empresa,DC=local");
        when(config.get("AD_LDAP_BIND_DN", "")).thenReturn("svc");
        when(config.get("AD_LDAP_BIND_PASSWORD", "")).thenReturn("pw");
        when(config.get("AD_LOCAL_FALLBACK_ENABLED", "true")).thenReturn("true");
        when(config.getInt("AD_DEFAULT_ACCESS_GROUP_ID", 2)).thenReturn(2);

        var result = ldapClient.authenticate("qualquer", "qualquer");

        assertThat(result).isEmpty();
        verifyNoInteractions(templateFactory);
    }

    @Test
    void authenticate_hostVazio_naoTentaBindMesmoHabilitado() {
        when(config.get("AD_LDAP_ENABLED", "false")).thenReturn("true");
        when(config.get("AD_LDAP_HOST", "")).thenReturn("");
        when(config.getInt("AD_LDAP_PORT", 636)).thenReturn(636);
        when(config.get("AD_LDAP_USE_SSL", "true")).thenReturn("true");
        when(config.get("AD_LDAP_BASE_DN", "")).thenReturn("");
        when(config.get("AD_LDAP_BIND_DN", "")).thenReturn("");
        when(config.get("AD_LDAP_BIND_PASSWORD", "")).thenReturn("");
        when(config.get("AD_LOCAL_FALLBACK_ENABLED", "true")).thenReturn("true");
        when(config.getInt("AD_DEFAULT_ACCESS_GROUP_ID", 2)).thenReturn(2);

        assertThat(ldapClient.authenticate("qualquer", "qualquer")).isEmpty();
        verifyNoInteractions(templateFactory);
    }

    @Test
    void authenticate_bindFalha_retornaEmptySemLancar() {
        stubEnabledConfig();
        when(templateFactory.create(any())).thenReturn(ldapTemplate);
        when(
                        ldapTemplate.authenticate(
                                any(org.springframework.ldap.query.LdapQuery.class),
                                any(String.class),
                                any(org.springframework.ldap.core.AuthenticatedLdapEntryContextMapper.class)))
                .thenThrow(new RuntimeException("bind failed"));

        assertThat(ldapClient.authenticate("usuario", "senhaErrada")).isEmpty();
    }

    private void stubEnabledConfig() {
        when(config.get("AD_LDAP_ENABLED", "false")).thenReturn("true");
        when(config.get("AD_LDAP_HOST", "")).thenReturn("dc.empresa.local");
        when(config.getInt("AD_LDAP_PORT", 636)).thenReturn(636);
        when(config.get("AD_LDAP_USE_SSL", "true")).thenReturn("true");
        when(config.get("AD_LDAP_BASE_DN", "")).thenReturn("DC=empresa,DC=local");
        when(config.get("AD_LDAP_BIND_DN", "")).thenReturn("svc");
        when(config.get("AD_LDAP_BIND_PASSWORD", "")).thenReturn("pw");
        when(config.get("AD_LOCAL_FALLBACK_ENABLED", "true")).thenReturn("true");
        when(config.getInt("AD_DEFAULT_ACCESS_GROUP_ID", 2)).thenReturn(2);
    }
}
