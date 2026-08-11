package com.asteriskia.domain.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * SecurityJailServiceTest — teste de caracterização (fase 11 da refatoração). Cobre a orquestração
 * de jail (combinação de config + status, toggle, sincronismo de ignoreip) extraída de
 * SecurityController.
 */
class SecurityJailServiceTest {

    @Mock private FailToBanClient f2b;
    @Mock private JailConfigRepository jailConfigRepo;

    private SecurityJailService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SecurityJailService(f2b, jailConfigRepo);
    }

    @Test
    void isManaged_reconheceAsTresJailsGerenciadas() {
        assertThat(service.isManaged("asterisk-auth")).isTrue();
        assertThat(service.isManaged("asterisk-scan")).isTrue();
        assertThat(service.isManaged("asterisk-flood")).isTrue();
        assertThat(service.isManaged("outra-jail")).isFalse();
    }

    @Test
    void isFail2banRunning_delegaParaFailToBanClient() {
        when(f2b.isRunning()).thenReturn(true);

        assertThat(service.isFail2banRunning()).isTrue();
    }

    @Test
    void jailInfo_f2bRodando_combinaConfigComStatus() {
        when(jailConfigRepo.parseJailConfig("asterisk-auth"))
                .thenReturn(
                        Map.of(
                                "enabled", "true",
                                "maxretry", "10",
                                "banaction", "nftables-multiport"));
        when(f2b.exec("status", "asterisk-auth")).thenReturn("status-raw");
        when(f2b.parseBannedCount("status-raw")).thenReturn(3);
        when(f2b.parseTotalFailed("status-raw")).thenReturn(42);

        Map<String, Object> info = service.jailInfo("asterisk-auth", true);

        assertThat(info)
                .containsEntry("name", "asterisk-auth")
                .containsEntry("enabled", true)
                .containsEntry("maxretry", 10)
                .containsEntry("banaction", "nftables-multiport")
                .containsEntry("currentlyBanned", 3)
                .containsEntry("totalFailed", 42);
    }

    @Test
    void jailInfo_f2bParado_devolveContadoresZerados() {
        when(jailConfigRepo.parseJailConfig("asterisk-auth")).thenReturn(Map.of());

        Map<String, Object> info = service.jailInfo("asterisk-auth", false);

        assertThat(info).containsEntry("currentlyBanned", 0).containsEntry("totalFailed", 0);
    }

    @Test
    void allJailInfo_devolveAsTresJailsNaOrdemDeclarada() {
        when(jailConfigRepo.parseJailConfig(anyString())).thenReturn(Map.of());

        List<Map<String, Object>> all = service.allJailInfo(false);

        assertThat(all).hasSize(3);
        assertThat(all.stream().map(m -> m.get("name")))
                .containsExactly("asterisk-auth", "asterisk-scan", "asterisk-flood");
    }

    @Test
    void toggleJail_habilitaEForcaReload() throws IOException {
        when(f2b.exec("reload", "asterisk-auth")).thenReturn("OK");

        String reload = service.toggleJail("asterisk-auth", true);

        assertThat(reload).isEqualTo("OK");
        verify(jailConfigRepo).updateJailParam("asterisk-auth", "enabled", "true");
    }

    @Test
    void toggleJail_desabilita() throws IOException {
        service.toggleJail("asterisk-scan", false);

        verify(jailConfigRepo).updateJailParam("asterisk-scan", "enabled", "false");
    }

    @Test
    void updateIgnoreIp_propagaWhitelistParaAsTresJails() throws IOException {
        service.updateIgnoreIp(List.of("1.2.3.4", "5.6.7.8"));

        verify(jailConfigRepo).updateJailParam("asterisk-auth", "ignoreip", "1.2.3.4 5.6.7.8");
        verify(jailConfigRepo).updateJailParam("asterisk-scan", "ignoreip", "1.2.3.4 5.6.7.8");
        verify(jailConfigRepo).updateJailParam("asterisk-flood", "ignoreip", "1.2.3.4 5.6.7.8");
    }

    @Test
    void updateIgnoreIp_listaVazia_gravaIgnoreipVazio() throws IOException {
        service.updateIgnoreIp(List.of());

        verify(jailConfigRepo).updateJailParam(eq("asterisk-auth"), eq("ignoreip"), eq(""));
    }
}
