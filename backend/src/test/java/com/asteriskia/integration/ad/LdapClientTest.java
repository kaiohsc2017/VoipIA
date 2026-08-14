package com.asteriskia.integration.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.asteriskia.domain.config.ConfigService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQuery;

@ExtendWith(MockitoExtension.class)
class LdapClientTest {

    @Mock private ConfigService config;
    @Mock private LdapTemplateFactory templateFactory;
    @Mock private LdapTemplate ldapTemplate;

    private LdapClient ldapClient;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        ldapClient = new LdapClient(config, templateFactory);
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(LdapClient.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(LdapClient.class)).detachAppender(logAppender);
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

    @Test
    void fetchAll_abaixoDoLimite_naoLogaAviso() {
        stubEnabledConfig();
        when(templateFactory.create(any())).thenReturn(ldapTemplate);
        when(ldapTemplate.search(any(LdapQuery.class), any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenReturn(List.of(mock(LdapUserAttributes.class), mock(LdapUserAttributes.class)));

        var result = ldapClient.fetchAll();

        assertThat(result).hasSize(2);
        assertThat(logAppender.list).noneMatch(e -> e.getFormattedMessage().contains("truncamento"));
    }

    @Test
    void fetchAll_atingeLimiteSuspeito_logaAvisoDeTruncamento() {
        // Fase 10 (D1): resultado exatamente no teto de página padrão do AD (1000) é sinal forte
        // de truncamento silencioso — usuário desabilitado fora da página nunca seria visto pelo
        // sync. Sem PagedResultsControl implementado (fora de escopo desta fatia), o mínimo é
        // avisar em vez de silêncio total.
        stubEnabledConfig();
        when(templateFactory.create(any())).thenReturn(ldapTemplate);
        var mockUsers = java.util.stream.Stream.generate(() -> mock(LdapUserAttributes.class)).limit(1000).toList();
        when(ldapTemplate.search(any(LdapQuery.class), any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenReturn(mockUsers);

        var result = ldapClient.fetchAll();

        assertThat(result).hasSize(1000);
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage().contains("truncamento"));
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
