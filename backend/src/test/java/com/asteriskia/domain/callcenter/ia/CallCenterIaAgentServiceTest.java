package com.asteriskia.domain.callcenter.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CallCenterIaAgentServiceTest {

    @Mock private CcIaAgentRepository repository;
    @Mock private CcQueueRepository queueRepository;

    private CallCenterIaAgentService service;

    @BeforeEach
    void setUp() {
        service = new CallCenterIaAgentService(repository, queueRepository);
    }

    private IaAgentRequest validRequest() {
        return new IaAgentRequest(
                "Suporte N1", "Persona de suporte", "Você é cordial.", "Olá, como posso ajudar?",
                "gemini-2.5-flash", new BigDecimal("0.20"), 4, new BigDecimal("0.55"), null, 5,
                new BigDecimal("0.10"), null);
    }

    @Nested
    class Criacao {

        @Test
        void create_nomeDuplicado_deveLancar409() {
            when(repository.existsByNameIgnoreCase("Suporte N1")).thenReturn(true);

            assertThatThrownBy(() -> service.create(validRequest()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409");

            verify(repository, never()).save(any());
        }

        @Test
        void create_modeloForaDaAllowlist_deveLancar400() {
            when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
            var request =
                    new IaAgentRequest(
                            "Suporte N1", null, "prompt", null, "gpt-4o", null, null, null, null,
                            null, null, null);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400");

            verify(repository, never()).save(any());
        }

        @Test
        void create_maxCostUsdNegativo_deveLancar400() {
            when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
            var request =
                    new IaAgentRequest(
                            "Suporte N1", null, "prompt", null, "gemini-2.5-flash", null, null, null,
                            null, null, new BigDecimal("-1"), null);

            assertThatThrownBy(() -> service.create(request)).isInstanceOf(ResponseStatusException.class);
            verify(repository, never()).save(any());
        }

        @Test
        void create_fallbackQueueIdInexistente_deveLancar400() {
            when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(queueRepository.findById(99L)).thenReturn(Optional.empty());
            var request =
                    new IaAgentRequest(
                            "Suporte N1", null, "prompt", null, "gemini-2.5-flash", null, null, null,
                            null, null, null, 99L);

            assertThatThrownBy(() -> service.create(request)).isInstanceOf(ResponseStatusException.class);
            verify(repository, never()).save(any());
        }

        @Test
        void create_valoresForaDaFaixa_devemSerClampados() {
            when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(repository.save(any(CcIaAgent.class))).thenAnswer(inv -> inv.getArgument(0));
            var request =
                    new IaAgentRequest(
                            "Suporte N1", null, "prompt", null, "gemini-2.5-flash", new BigDecimal("9"),
                            999, new BigDecimal("5"), null, 999, null, null);

            var saved = service.create(request);

            assertThat(saved.getTemperature()).isEqualByComparingTo("2");
            assertThat(saved.getMaxTurns()).isEqualTo(20);
            assertThat(saved.getTopK()).isEqualTo(50);
            assertThat(saved.getMatchThreshold()).isEqualByComparingTo("1");
        }

        @Test
        void create_topKEMatchThresholdNegativos_devemSerClampados() {
            when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(repository.save(any(CcIaAgent.class))).thenAnswer(inv -> inv.getArgument(0));
            var request =
                    new IaAgentRequest(
                            "Suporte N1", null, "prompt", null, "gemini-2.5-flash", null,
                            -5, new BigDecimal("-1"), null, null, null, null);

            var saved = service.create(request);

            assertThat(saved.getTopK()).isEqualTo(1);
            assertThat(saved.getMatchThreshold()).isEqualByComparingTo("0");
        }

        @Test
        void create_valido_devePersistir() {
            when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(repository.save(any(CcIaAgent.class))).thenAnswer(inv -> inv.getArgument(0));

            var saved = service.create(validRequest());

            assertThat(saved.getName()).isEqualTo("Suporte N1");
            assertThat(saved.getModel()).isEqualTo("gemini-2.5-flash");
            assertThat(saved.getActive()).isTrue();
        }
    }

    @Nested
    class Atualizacao {

        @Test
        void update_inexistente_deveLancar404() {
            when(repository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(1L, validRequest()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void update_nomeDuplicadoComOutroId_deveLancar409() {
            var existing = CcIaAgent.builder().id(1L).name("Antigo").build();
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.existsByNameIgnoreCaseAndIdNot("Suporte N1", 1L)).thenReturn(true);

            assertThatThrownBy(() -> service.update(1L, validRequest()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409");
        }
    }

    @Nested
    class Delecao {

        @Test
        void delete_deveDesativarSemRemoverLinha() {
            var existing = CcIaAgent.builder().id(1L).name("X").active(true).build();
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(CcIaAgent.class))).thenAnswer(inv -> inv.getArgument(0));

            service.delete(1L);

            verify(repository).save(existing);
            assertThat(existing.getActive()).isFalse();
            verify(repository, never()).deleteById(anyLong());
        }
    }
}
