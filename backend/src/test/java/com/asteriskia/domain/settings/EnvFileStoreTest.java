package com.asteriskia.domain.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * EnvFileStoreTest — teste de caracterização (fase 9 da refatoração). Cobre o parse/mascaramento do
 * .env, a reescrita preservando comentários/ordem e o backup com poda — mecânica extraída de
 * SettingsService.
 */
class EnvFileStoreTest {

    private EnvFileStore store;
    private Path envFile;

    @TempDir private Path tempDir;

    @BeforeEach
    void setUp() {
        store = new EnvFileStore();
        envFile = tempDir.resolve(".env");
        ReflectionTestUtils.setField(store, "settingsFilePath", envFile.toString());
    }

    private void write(String content) throws IOException {
        Files.writeString(envFile, content, StandardCharsets.UTF_8);
    }

    @Test
    void isSecretKey_reconheceQualquerSufixoDeSegredoConhecido() {
        assertThat(EnvFileStore.isSecretKey("GEMINI_API_KEY")).isTrue();
        assertThat(EnvFileStore.isSecretKey("RAMAL_9001_PASSWORD")).isTrue();
        assertThat(EnvFileStore.isSecretKey("AGENTS_LLM_OPENAI_TOKEN")).isTrue();
        assertThat(EnvFileStore.isSecretKey("BACKEND_JWT_SECRET")).isTrue();
        assertThat(EnvFileStore.isSecretKey("JIRA_ISSUE_TYPE")).isFalse();
    }

    @Test
    void readEntries_arquivoInexistente_devolveListaVazia() throws IOException {
        assertThat(store.readEntries()).isEmpty();
    }

    @Test
    void readEntries_classificaFieldComentarioEBranco_mascarandoChavesSecretas()
            throws IOException {
        write("# comentario\n" + "\n" + "GEMINI_API_KEY=abc123\n" + "JIRA_ISSUE_TYPE=Task\n");

        List<EnvEntry> entries = store.readEntries();

        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).type()).isEqualTo(EnvEntry.Type.COMMENT);
        assertThat(entries.get(1).type()).isEqualTo(EnvEntry.Type.BLANK);
        assertThat(entries.get(2).key()).isEqualTo("GEMINI_API_KEY");
        assertThat(entries.get(2).isSecret()).isTrue();
        assertThat(entries.get(2).value()).isEqualTo(EnvFileStore.MASK_DISPLAY);
        assertThat(entries.get(3).key()).isEqualTo("JIRA_ISSUE_TYPE");
        assertThat(entries.get(3).isSecret()).isFalse();
        assertThat(entries.get(3).value()).isEqualTo("Task");
    }

    @Test
    void readRaw_arquivoInexistente_devolveMapaVazio() throws IOException {
        assertThat(store.readRaw()).isEmpty();
    }

    @Test
    void readRaw_ignoraComentariosEBrancosEDevolveValoresSemMascara() throws IOException {
        write("# nota\n\nGEMINI_API_KEY=segredo-real\nJIRA_ISSUE_TYPE=Task\n");

        assertThat(store.readRaw())
                .containsOnly(
                        Map.entry("GEMINI_API_KEY", "segredo-real"),
                        Map.entry("JIRA_ISSUE_TYPE", "Task"));
    }

    @Test
    void rewrite_preservaComentariosEOrdemEAtualizaSoAsChavesEnviadas() throws IOException {
        write("# cabecalho\nJIRA_ISSUE_TYPE=Task\nOUTRA_CHAVE=valor\n");

        store.rewrite(Map.of("JIRA_ISSUE_TYPE", "Support"));

        assertThat(Files.readAllLines(envFile, StandardCharsets.UTF_8))
                .containsExactly("# cabecalho", "JIRA_ISSUE_TYPE=Support", "OUTRA_CHAVE=valor");
    }

    @Test
    void rewrite_appendaChavesNovasQueNaoExistiamNoArquivo() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        store.rewrite(Map.of("NOVA_CHAVE", "novo-valor"));

        assertThat(Files.readAllLines(envFile, StandardCharsets.UTF_8))
                .containsExactly("JIRA_ISSUE_TYPE=Task", "NOVA_CHAVE=novo-valor");
    }

    @Test
    void rewrite_arquivoInexistente_criaArquivoNovoComAsChaves() throws IOException {
        store.rewrite(Map.of("NOVA_CHAVE", "novo-valor"));

        assertThat(Files.readAllLines(envFile, StandardCharsets.UTF_8))
                .containsExactly("NOVA_CHAVE=novo-valor");
    }

    @Test
    void backup_criaArquivoComSufixoTimestampNoMesmoDiretorio() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        store.backup();

        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                    .anyMatch(name -> name.startsWith(".env.bak-" + today));
        }
    }

    @Test
    void backup_arquivoInexistente_naoFalhaNemCriaBackup() throws IOException {
        store.backup();

        try (var stream = Files.list(tempDir)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void backup_maisDeDezBackups_removeOsMaisAntigosMantendoDez() throws IOException {
        write("JIRA_ISSUE_TYPE=Task\n");

        // Cria 11 backups "manuais" com timestamps distintos (o backup real via store.backup()
        // usa o segundo corrente — criar 11 no mesmo teste colidiria no nome).
        for (int i = 0; i < 11; i++) {
            Files.writeString(
                    tempDir.resolve(".env.bak-2026010%d-000000".formatted(i)), "conteudo");
        }

        store.backup();

        try (var stream = Files.list(tempDir)) {
            long backupCount =
                    stream.map(p -> p.getFileName().toString())
                            .filter(name -> name.startsWith(".env.bak-"))
                            .count();
            assertThat(backupCount).isEqualTo(10);
        }
    }
}
