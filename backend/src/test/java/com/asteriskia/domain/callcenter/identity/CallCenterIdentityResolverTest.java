package com.asteriskia.domain.callcenter.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.ai.AiProviderService;
import com.asteriskia.integration.ad.AdUser;
import com.asteriskia.integration.ad.AdUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

class CallCenterIdentityResolverTest {

    private AdUserRepository adUserRepository;
    private AiProviderService aiProviderService;
    private CcIdentityResolutionLogRepository resolutionLogRepository;
    private CallCenterIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        adUserRepository = mock(AdUserRepository.class);
        aiProviderService = mock(AiProviderService.class);
        resolutionLogRepository = mock(CcIdentityResolutionLogRepository.class);
        resolver = new CallCenterIdentityResolver(
                adUserRepository,
                aiProviderService,
                resolutionLogRepository,
                new ObjectMapper(),
                WebClient.builder());
    }

    @Test
    @DisplayName("resolveByLogin encontra usuário AD ignorando caixa e marca a origem informada")
    void resolveByLogin_found_returnsExactMatch() {
        var adUser = AdUser.builder().id(1L).samAccountName("jsilva").displayName("João Silva").build();
        when(adUserRepository.findBySamAccountNameIgnoreCase("JSILVA")).thenReturn(Optional.of(adUser));

        var result = resolver.resolveByLogin("JSILVA", IdentitySource.NETWORK_LOGIN);

        assertThat(result).isPresent();
        assertThat(result.get().adUser()).isEqualTo(adUser);
        assertThat(result.get().source()).isEqualTo(IdentitySource.NETWORK_LOGIN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("resolveByLogin nunca chama o repositório para login em branco/nulo")
    void resolveByLogin_blank_returnsEmptyWithoutQuerying(String login) {
        var result = resolver.resolveByLogin(login, IdentitySource.URA_INPUT);

        assertThat(result).isEmpty();
        verify(adUserRepository, never()).findBySamAccountNameIgnoreCase(anyString());
    }

    @Test
    @DisplayName("resolveByAni normaliza o ANI a só dígitos antes de comparar com telephone_number")
    void resolveByAni_normalizesDigitsBeforeLookup() {
        var adUser = AdUser.builder().id(2L).samAccountName("mcosta").telephoneNumber("11987654321").build();
        when(adUserRepository.findByTelephoneNumber("11987654321")).thenReturn(Optional.of(adUser));

        var result = resolver.resolveByAni("+55 (11) 98765-4321");

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(IdentitySource.ANI);
    }

    @Test
    @DisplayName("resolveByAni sem correspondência retorna vazio, nunca lança exceção")
    void resolveByAni_noMatch_returnsEmpty() {
        when(adUserRepository.findByTelephoneNumber(anyString())).thenReturn(Optional.empty());

        var result = resolver.resolveByAni("0800123456");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findFuzzyCandidateByName descarta candidato abaixo do limiar de similaridade")
    void findFuzzyCandidateByName_belowThreshold_returnsEmpty() {
        var match = mock(AdUserRepository.AdUserMatchProjection.class);
        when(match.getId()).thenReturn(9L);
        when(match.getScore()).thenReturn(0.05);
        when(adUserRepository.findBestFuzzyMatchByDisplayName(anyString())).thenReturn(Optional.of(match));

        var result = resolver.findFuzzyCandidateByName("Nome Qualquer");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findFuzzyCandidateByName acima do limiar retorna o AdUser correspondente")
    void findFuzzyCandidateByName_aboveThreshold_returnsMatch() {
        var adUser = AdUser.builder().id(9L).samAccountName("psantos").displayName("Paulo Santos").build();
        var match = mock(AdUserRepository.AdUserMatchProjection.class);
        when(match.getId()).thenReturn(9L);
        when(match.getScore()).thenReturn(0.6);
        when(adUserRepository.findBestFuzzyMatchByDisplayName(anyString())).thenReturn(Optional.of(match));
        when(adUserRepository.findById(9L)).thenReturn(Optional.of(adUser));

        var result = resolver.findFuzzyCandidateByName("Paulo Santos");

        assertThat(result).isPresent();
        assertThat(result.get().adUser()).isEqualTo(adUser);
        assertThat(result.get().source()).isEqualTo(IdentitySource.URA_INPUT);
    }

    @ParameterizedTest
    @CsvSource({
        "sim, true",
        "SIM, true",
        "isso mesmo, true",
        "correto, true",
        "não, false",
        "nao, false",
        "'', false"
    })
    @DisplayName("isSpokenConfirmationPositive é tolerante a variação de fala mas fail-closed por padrão")
    void isSpokenConfirmationPositive_variousInputs(String transcript, boolean expected) {
        assertThat(resolver.isSpokenConfirmationPositive(transcript)).isEqualTo(expected);
    }

    @Test
    @DisplayName("isSpokenConfirmationPositive(null) é sempre negativo — fail-closed")
    void isSpokenConfirmationPositive_null_isFalse() {
        assertThat(resolver.isSpokenConfirmationPositive(null)).isFalse();
    }

    @Test
    @DisplayName("transcribe sem API key configurada não chama a rede e retorna transcript vazio")
    void transcribe_withoutApiKey_returnsEmpty() {
        when(aiProviderService.getRawKey("gemini")).thenReturn("");

        var result = resolver.transcribe(new byte[] {1, 2, 3});

        assertThat(result.transcript()).isEmpty();
    }

    @Test
    @DisplayName("logResolution persiste o log de custo com o desfecho informado")
    void logResolution_persistsLog() {
        resolver.logResolution("voice", "resolved", java.math.BigDecimal.TEN);

        verify(resolutionLogRepository).save(any(CcIdentityResolutionLog.class));
    }
}
