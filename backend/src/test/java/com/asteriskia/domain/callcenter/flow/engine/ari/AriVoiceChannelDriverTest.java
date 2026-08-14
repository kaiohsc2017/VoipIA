package com.asteriskia.domain.callcenter.flow.engine.ari;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AriVoiceChannelDriverTest — cobre {@code transferToExtension} (Fase 5e.2): validação estrita do
 * ramal ANTES de qualquer chamada ao ARI (mesma classe do achado de {@code AriClient.play}, Fase
 * 10, H1) — nunca interpolar entrada não validada.
 */
class AriVoiceChannelDriverTest {

    private final AriClient ariClient = mock(AriClient.class);
    private final AriPlaybackTracker playbackTracker = mock(AriPlaybackTracker.class);
    private final AriRecordingTracker recordingTracker = mock(AriRecordingTracker.class);
    private final AriVoiceChannelDriver driver =
            new AriVoiceChannelDriver(ariClient, playbackTracker, recordingTracker, "chan-1", "ramais-internos");

    @Test
    @DisplayName("transferToExtension com ramal válido (3-4 dígitos) encaminha via continueInDialplan")
    void transferToExtension_validExtension_continuesInDialplan() {
        driver.transferToExtension("4001");

        verify(ariClient).continueInDialplan("chan-1", "ramais-internos", "4001", 1);
    }

    @Test
    @DisplayName("transferToExtension com ramal de 3 dígitos (ex.: ramal fixo 1001) também é aceito")
    void transferToExtension_threeDigitExtension_continuesInDialplan() {
        driver.transferToExtension("1001");

        verify(ariClient).continueInDialplan("chan-1", "ramais-internos", "1001", 1);
    }

    @Test
    @DisplayName("transferToExtension recusa ramal com letras/sintaxe de função — nunca chama o ARI")
    void transferToExtension_invalidExtensionWithFunctionSyntax_neverCallsAri() {
        driver.transferToExtension("FILE(/etc/asterisk/manager.conf,0,0)");

        verify(ariClient, never()).continueInDialplan(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("transferToExtension recusa ramal fora do tamanho esperado (5+ dígitos)")
    void transferToExtension_tooLongExtension_neverCallsAri() {
        driver.transferToExtension("400100");

        verify(ariClient, never()).continueInDialplan(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("transferToExtension com null nunca chama o ARI")
    void transferToExtension_nullExtension_neverCallsAri() {
        driver.transferToExtension(null);

        verify(ariClient, never()).continueInDialplan(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }
}
