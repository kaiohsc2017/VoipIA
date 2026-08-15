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
    void fetchAll_umaPagina_retornaTodosOsUsuarios() {
        stubEnabledConfig();
        when(templateFactory.create(any())).thenReturn(ldapTemplate);
        when(ldapTemplate.search(
                        any(String.class),
                        any(String.class),
                        any(javax.naming.directory.SearchControls.class),
                        any(org.springframework.ldap.core.AttributesMapper.class),
                        any(org.springframework.ldap.control.PagedResultsDirContextProcessor.class)))
                .thenAnswer(
                        inv -> {
                            org.springframework.ldap.control.PagedResultsDirContextProcessor processor =
                                    inv.getArgument(4);
                            setCookie(processor, null);
                            return List.of(mock(LdapUserAttributes.class), mock(LdapUserAttributes.class));
                        });

        var result = ldapClient.fetchAll();

        assertThat(result).hasSize(2);
        verify(ldapTemplate, times(1))
                .search(
                        any(String.class),
                        any(String.class),
                        any(javax.naming.directory.SearchControls.class),
                        any(org.springframework.ldap.core.AttributesMapper.class),
                        any(org.springframework.ldap.control.PagedResultsDirContextProcessor.class));
    }

    @Test
    void fetchAll_multiplasPaginas_concatenaTodasSemTruncar() {
        // Antes da paginação real, um resultado exatamente no teto de página do AD (ex: 1000)
        // era sinal de truncamento silencioso — um usuário desabilitado fora da página nunca
        // era visto pelo sync. Com PagedResultsDirContextProcessor, o cookie não-nulo da 1ª
        // página força uma 2ª chamada, provando que nada é descartado.
        stubEnabledConfig();
        when(templateFactory.create(any())).thenReturn(ldapTemplate);
        var page1 = java.util.stream.Stream.generate(() -> mock(LdapUserAttributes.class)).limit(500).toList();
        var page2 = java.util.stream.Stream.generate(() -> mock(LdapUserAttributes.class)).limit(200).toList();
        var callCount = new int[] {0};
        when(ldapTemplate.search(
                        any(String.class),
                        any(String.class),
                        any(javax.naming.directory.SearchControls.class),
                        any(org.springframework.ldap.core.AttributesMapper.class),
                        any(org.springframework.ldap.control.PagedResultsDirContextProcessor.class)))
                .thenAnswer(
                        inv -> {
                            org.springframework.ldap.control.PagedResultsDirContextProcessor processor =
                                    inv.getArgument(4);
                            callCount[0]++;
                            if (callCount[0] == 1) {
                                setCookie(processor, new byte[] {1, 2, 3});
                                return page1;
                            }
                            setCookie(processor, null);
                            return page2;
                        });

        var result = ldapClient.fetchAll();

        assertThat(result).hasSize(700);
        verify(ldapTemplate, times(2))
                .search(
                        any(String.class),
                        any(String.class),
                        any(javax.naming.directory.SearchControls.class),
                        any(org.springframework.ldap.core.AttributesMapper.class),
                        any(org.springframework.ldap.control.PagedResultsDirContextProcessor.class));
    }

    /** Injeta o cookie no processor via reflection — a API do Spring LDAP só expõe getCookie(). */
    private static void setCookie(
            org.springframework.ldap.control.PagedResultsDirContextProcessor processor, byte[] cookieBytes)
            throws Exception {
        var field =
                org.springframework.ldap.control.PagedResultsDirContextProcessor.class.getDeclaredField("cookie");
        field.setAccessible(true);
        field.set(
                processor,
                cookieBytes == null
                        ? null
                        : new org.springframework.ldap.control.PagedResultsCookie(cookieBytes));
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
