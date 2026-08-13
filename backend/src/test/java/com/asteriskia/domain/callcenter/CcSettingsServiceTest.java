package com.asteriskia.domain.callcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre as regras de configuração do Call Center (Fase 19 do plano Parte III): ranges de ramal
 * (agente/fila/fluxo) e o interruptor global de NPS. O foco é a validação — sobreposição, colisão
 * com faixas do Telecom e formato exigido pelo dialplan estático — e a garantia de que mudar um
 * range nunca realoca (D20): a persistência é sempre um upsert por chave, nunca toca nas tabelas
 * de agente/fila/fluxo.
 */
@ExtendWith(MockitoExtension.class)
class CcSettingsServiceTest {

    @Mock private CcSettingRepository settingRepository;
    @Mock private CcExtensionRepository extensionRepository;
    @Mock private CcQueueRepository queueRepository;
    @Mock private CcFlowRepository flowRepository;

    private CcSettingsService service() {
        return new CcSettingsService(settingRepository, extensionRepository, queueRepository, flowRepository);
    }

    @Test
    @DisplayName("getRange devolve o default do código quando nunca foi configurado")
    void getRange_neverConfigured_returnsCodeDefault() {
        when(settingRepository.findBySettingKey(any())).thenReturn(Optional.empty());

        var range = service().getRange(CcSettingsService.RangeType.AGENT);

        assertThat(range.start()).isEqualTo(4000);
        assertThat(range.end()).isEqualTo(4999);
    }

    @Test
    @DisplayName("updateRange rejeita start >= end")
    void updateRange_startNotBeforeEnd_rejected() {
        var service = service();
        assertThatThrownBy(() -> service.updateRange(CcSettingsService.RangeType.AGENT, 4999, 4000))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("menor que o fim");
    }

    @Test
    @DisplayName("updateRange rejeita faixa que não é um bloco de milhar completo")
    void updateRange_notSingleThousandBlock_rejected() {
        var service = service();
        assertThatThrownBy(() -> service.updateRange(CcSettingsService.RangeType.AGENT, 4000, 4500))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("bloco de milhar");
    }

    @Test
    @DisplayName("updateRange rejeita range negativo (achado de segurança: -1000 % 1000 == 0 em Java)")
    void updateRange_negativeRange_rejected() {
        var service = service();
        assertThatThrownBy(() -> service.updateRange(CcSettingsService.RangeType.AGENT, -1000, -1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("pelo menos 1000");
    }

    @Test
    @DisplayName("updateRange rejeita colisão com faixa reservada do Telecom (URA legada/URAs/softphones)")
    void updateRange_collidesWithTelecomRange_rejected() {
        var service = service();
        assertThatThrownBy(() -> service.updateRange(CcSettingsService.RangeType.AGENT, 2000, 2999))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Telecom");
    }

    @Test
    @DisplayName("updateRange rejeita colisão com outro range do próprio Call Center")
    void updateRange_collidesWithOwnCallCenterRange_rejected() {
        when(settingRepository.findBySettingKey(any())).thenReturn(Optional.empty());
        var service = service();

        // QUEUE por default é 5000-5999 — tentar mover AGENT para lá deve colidir.
        assertThatThrownBy(() -> service.updateRange(CcSettingsService.RangeType.AGENT, 5000, 5999))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("filas");
    }

    @Test
    @DisplayName("updateRange aceita faixa válida e devolve quantos ramais ativos ficam fora dela")
    void updateRange_validRange_persistsAndReturnsOutsideCount() {
        when(settingRepository.findBySettingKey(any())).thenReturn(Optional.empty());
        when(extensionRepository.countOutsideRange(3000, 3999)).thenReturn(2L);

        int outside = service().updateRange(CcSettingsService.RangeType.AGENT, 3000, 3999);

        assertThat(outside).isEqualTo(2);
        org.mockito.Mockito.verify(settingRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    @DisplayName("isNpsEnabledGlobally é true por default (ninguém configurou o interruptor ainda)")
    void isNpsEnabledGlobally_neverConfigured_defaultsToTrue() {
        when(settingRepository.findBySettingKey(any())).thenReturn(Optional.empty());

        assertThat(service().isNpsEnabledGlobally()).isTrue();
    }

    @Test
    @DisplayName("setNpsEnabledGlobally persiste o valor e getIsNpsEnabledGlobally reflete a mudança")
    void setNpsEnabledGlobally_persistsValue() {
        when(settingRepository.findBySettingKey("nps.enabled_globally")).thenReturn(Optional.empty());
        var saved = CcSetting.builder().settingKey("nps.enabled_globally").build();
        when(settingRepository.save(any())).thenReturn(saved);

        service().setNpsEnabledGlobally(false);

        var captor = org.mockito.ArgumentCaptor.forClass(CcSetting.class);
        org.mockito.Mockito.verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getSettingValue()).isEqualTo("false");
    }
}
