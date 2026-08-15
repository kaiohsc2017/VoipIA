package com.asteriskia.domain.callcenter.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ContactProfileServiceTest — cobre a fachada síncrona do copiloto (Fase 16.2): reuso dentro da
 * janela de cache, disparo de geração quando ausente/vencido, e o dedup de {@code inFlight} —
 * nunca dispara uma segunda geração para o mesmo contato enquanto a primeira ainda não terminou.
 */
class ContactProfileServiceTest {

    private CcContactProfileRepository profileRepository;
    private ContactProfileGenerator generator;
    private CallCenterContactHistoryService historyService;
    private ContactProfileService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(CcContactProfileRepository.class);
        generator = mock(ContactProfileGenerator.class);
        historyService = mock(CallCenterContactHistoryService.class);
        service = new ContactProfileService(profileRepository, generator, historyService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "cacheHours", 24L);
        when(historyService.historyFor(anyString(), anyInt(), any(), any())).thenReturn(List.of());
        when(generator.generate(anyString(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    @DisplayName("sam vazio retorna UNAVAILABLE sem tocar em repositório/gerador")
    void blankSam_returnsUnavailable() {
        var result = service.getOrTrigger("", 1L);

        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        verify(generator, never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("sem perfil existente dispara geração e retorna GENERATING")
    void noExistingProfile_triggersGenerationAndReturnsGenerating() {
        when(profileRepository.findFirstByResolvedAdSamOrderByGeneratedAtDesc("jsilva")).thenReturn(Optional.empty());

        var result = service.getOrTrigger("jsilva", 10L);

        assertThat(result.status()).isEqualTo("GENERATING");
        verify(generator, times(1)).generate("jsilva", 10L, List.of());
    }

    @Test
    @DisplayName("perfil fresco (dentro da janela de cache) é reusado sem disparar geração nova")
    void freshProfile_isReusedWithoutTriggering() throws Exception {
        var content = new ContactProfileContent("resumo", "neutro", List.of("tema"), BigDecimal.valueOf(0.2), List.of());
        var profile = CcContactProfile.builder()
                .id(1L)
                .resolvedAdSam("jsilva")
                .profileJson(new ObjectMapper().writeValueAsString(content))
                .generatedAt(LocalDateTime.now().minusHours(1))
                .model("gemini-2.5-flash")
                .costUsd(BigDecimal.valueOf(0.001))
                .build();
        when(profileRepository.findFirstByResolvedAdSamOrderByGeneratedAtDesc("jsilva")).thenReturn(Optional.of(profile));

        var result = service.getOrTrigger("jsilva", 10L);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.resumoPerfil()).isEqualTo("resumo");
        verify(generator, never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("perfil vencido (fora da janela de cache) é retornado, mas uma regeração é disparada em segundo plano")
    void staleProfile_returnsExistingButTriggersRegeneration() throws Exception {
        var content = new ContactProfileContent("resumo antigo", "neutro", List.of(), BigDecimal.ZERO, List.of());
        var profile = CcContactProfile.builder()
                .id(1L)
                .resolvedAdSam("jsilva")
                .profileJson(new ObjectMapper().writeValueAsString(content))
                .generatedAt(LocalDateTime.now().minusHours(30))
                .build();
        when(profileRepository.findFirstByResolvedAdSamOrderByGeneratedAtDesc("jsilva")).thenReturn(Optional.of(profile));

        var result = service.getOrTrigger("jsilva", 10L);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.resumoPerfil()).isEqualTo("resumo antigo");
        verify(generator, times(1)).generate(any(), any(), any());
    }

    @Test
    @DisplayName("uma geração ainda em andamento (inFlight) nunca dispara uma segunda para o mesmo sam")
    void inFlight_dedupsConcurrentTriggers() {
        when(profileRepository.findFirstByResolvedAdSamOrderByGeneratedAtDesc("jsilva")).thenReturn(Optional.empty());
        var never = new CompletableFuture<Void>(); // nunca completa nesta chamada
        when(generator.generate(anyString(), any(), any())).thenReturn(never);

        service.getOrTrigger("jsilva", 10L);
        service.getOrTrigger("jsilva", 10L);

        verify(generator, times(1)).generate(any(), any(), any());
    }
}
