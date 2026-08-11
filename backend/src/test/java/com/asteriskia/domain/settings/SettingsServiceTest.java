package com.asteriskia.domain.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SettingsServiceTest — teste de caracterização (fase 9 da refatoração). Cobre a orquestração de
 * negócio (validação de chave, resolução da máscara de segredo, histórico de alterações e delegação
 * do job de apply) após extrair a mecânica de arquivo para EnvFileStore e o apply assíncrono para
 * SettingsApplyJobService.
 */
class SettingsServiceTest {

    @Mock private SettingsHistoryRepository historyRepository;
    @Mock private SettingsApplyJobService applyJobService;

    private EnvFileStore envFileStore;
    private SettingsService service;
    private Path envFile;

    @TempDir private Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        envFileStore = new EnvFileStore();
        envFile = tempDir.resolve(".env");
        ReflectionTestUtils.setField(envFileStore, "settingsFilePath", envFile.toString());
        service = new SettingsService(historyRepository, envFileStore, applyJobService);
    }

    private void write(String content) throws IOException {
        Files.writeString(envFile, content, StandardCharsets.UTF_8);
    }

    @Test
    void readSettings_delegaParaEnvFileStore() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        assertThat(service.readSettings()).hasSize(1);
    }

    @Test
    void readAsMap_devolveSoOsCamposComValorEFlagDeSegredo() throws IOException {
        write("# nota\nJIRA_ISSUE_TYPE=Task\n");

        Map<String, Object> map = service.readAsMap();

        assertThat(map).containsOnlyKeys("JIRA_ISSUE_TYPE");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) map.get("JIRA_ISSUE_TYPE");
        assertThat(entry).containsEntry("value", "Task").containsEntry("isSecret", false);
    }

    @Test
    void writeSettings_chaveForaDoFormatoDeIdentificador_lancaExcecaoSemTocarNoArquivo()
            throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        assertThatThrownBy(
                        () ->
                                service.writeSettings(
                                        Map.of("chave inválida", "x"), "admin", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.readString(envFile)).isEqualTo("JIRA_ISSUE_TYPE=Task\n");
    }

    @Test
    void writeSettings_preservaComentariosEOrdemEAtualizaSoAsChavesEnviadas() throws IOException {
        write("# cabecalho\nJIRA_ISSUE_TYPE=Task\nOUTRA_CHAVE=valor\n");

        service.writeSettings(Map.of("JIRA_ISSUE_TYPE", "Support"), "kaio", "10.0.0.1");

        List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
        assertThat(lines)
                .containsExactly("# cabecalho", "JIRA_ISSUE_TYPE=Support", "OUTRA_CHAVE=valor");
    }

    @Test
    void writeSettings_valorComMascaraSentinela_preservaValorAtualSemSobrescrever()
            throws IOException {
        write("GEMINI_API_KEY=segredo-real\n");

        service.writeSettings(Map.of("GEMINI_API_KEY", "••••••••"), "admin", "unknown");

        assertThat(Files.readString(envFile)).isEqualTo("GEMINI_API_KEY=segredo-real\n");
    }

    @Test
    void writeSettings_appendaChavesNovasQueNaoExistiamNoArquivo() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        service.writeSettings(Map.of("NOVA_CHAVE", "novo-valor"), "admin", "unknown");

        List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly("JIRA_ISSUE_TYPE=Task", "NOVA_CHAVE=novo-valor");
    }

    @Test
    void writeSettings_removeQuebrasDeLinhaDoValorParaEvitarInjecaoDeLinha() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        service.writeSettings(
                Map.of("JIRA_ISSUE_TYPE", "Valor\r\nCHAVE_INJETADA=malicioso"), "admin", "unknown");

        List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly("JIRA_ISSUE_TYPE=ValorCHAVE_INJETADA=malicioso");
    }

    @Test
    void writeSettings_criaBackupAntesDeSobrescrever() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        service.writeSettings(Map.of("JIRA_ISSUE_TYPE", "Support"), "admin", "unknown");

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                    .anyMatch(name -> name.startsWith(".env.bak-"));
        }
    }

    @Test
    void writeSettings_registraHistoricoApenasParaChavesQueMudaram() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\nOUTRA_CHAVE=igual\n");

        service.writeSettings(
                Map.of("JIRA_ISSUE_TYPE", "Support", "OUTRA_CHAVE", "igual"), "kaio", "10.0.0.1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettingsHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveAll(captor.capture());
        List<SettingsHistory> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getEnvKey()).isEqualTo("JIRA_ISSUE_TYPE");
        assertThat(saved.get(0).getOldValue()).isEqualTo("Task");
        assertThat(saved.get(0).getNewValue()).isEqualTo("Support");
    }

    @Test
    void writeSettings_semAlteracaoNenhuma_naoChamaSaveAll() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        service.writeSettings(Map.of("JIRA_ISSUE_TYPE", "Task"), "kaio", "10.0.0.1");

        verify(historyRepository, org.mockito.Mockito.never()).saveAll(anyList());
    }

    @Test
    void writeSettings_chaveSecreta_registraHistoricoMascarado() throws IOException {
        write("GEMINI_API_KEY=antigo\n");

        service.writeSettings(Map.of("GEMINI_API_KEY", "novo-valor"), "admin", "unknown");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettingsHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveAll(captor.capture());
        SettingsHistory saved = captor.getValue().get(0);
        assertThat(saved.getOldValue()).isEqualTo(EnvFileStore.MASK_DISPLAY);
        assertThat(saved.getNewValue()).isEqualTo(EnvFileStore.MASK_DISPLAY);
    }

    @Test
    void getHistory_delegaParaORepositorioComLimiteSaneado() {
        service.getHistory(500);

        verify(historyRepository).findAllByOrderByChangedAtDesc(PageRequest.of(0, 200));
    }

    @Test
    void startApplyAsync_delegaParaSettingsApplyJobService() {
        when(applyJobService.startApplyAsync(List.of("backend"))).thenReturn("job-1");

        assertThat(service.startApplyAsync(List.of("backend"))).isEqualTo("job-1");
    }

    @Test
    void startApplyAsyncSemArgumentos_delegaComListaVazia() {
        when(applyJobService.startApplyAsync(List.of())).thenReturn("job-2");

        assertThat(service.startApplyAsync()).isEqualTo("job-2");
    }

    @Test
    void getApplyStatus_delegaParaSettingsApplyJobService() {
        when(applyJobService.getApplyStatus("job-1")).thenReturn(Optional.empty());

        assertThat(service.getApplyStatus("job-1")).isEmpty();
    }
}
