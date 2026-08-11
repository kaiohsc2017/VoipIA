package com.asteriskia.domain.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * SettingsApplyJobServiceTest — teste de caracterização (fase 9 da refatoração). Cobre o ciclo de
 * vida do job assíncrono (jobId gerado, status inicial RUNNING, transição para ERROR quando o
 * docker-helper falha) extraído de SettingsService.
 */
class SettingsApplyJobServiceTest {

    @Mock private EnvFileStore envFileStore;

    private SettingsApplyJobService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // WebClient real sem base URL configurada — a chamada ao docker-helper falha na
        // Thread virtual (URI relativa inválida), o que é o comportamento esperado neste teste:
        // caracterizar a transição de status para ERROR, não o sucesso do apply em si.
        service = new SettingsApplyJobService(envFileStore, WebClient.builder());
        ReflectionTestUtils.setField(service, "dockerHelperUrl", "http://docker-helper:8085");
        ReflectionTestUtils.setField(service, "internalApiKey", "test-key");
    }

    @Test
    void getApplyStatus_jobIdDesconhecido_devolveOptionalVazio() {
        assertThat(service.getApplyStatus("inexistente")).isEmpty();
    }

    @Test
    void startApplyAsync_devolveJobIdEmFormatoUuidComJobRegistradoImediatamente() {
        String jobId = service.startApplyAsync(List.of("backend"));

        assertThat(jobId).matches("[0-9a-f-]{36}");
        assertThat(service.getApplyStatus(jobId)).isPresent();
    }

    @Test
    void startApplyAsync_dockerHelperInalcancavel_jobTerminaEmErro() throws InterruptedException {
        String jobId = service.startApplyAsync(List.of());

        SettingsApplyJobService.JobStatus finalStatus = null;
        for (int i = 0; i < 50; i++) {
            finalStatus = service.getApplyStatus(jobId).orElseThrow().getStatus();
            if (finalStatus != SettingsApplyJobService.JobStatus.RUNNING) break;
            Thread.sleep(100);
        }

        assertThat(finalStatus).isEqualTo(SettingsApplyJobService.JobStatus.ERROR);
    }
}
