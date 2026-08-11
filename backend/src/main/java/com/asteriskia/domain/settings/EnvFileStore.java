package com.asteriskia.domain.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * EnvFileStore — leitura, reescrita e backup mecânicos do arquivo .env, extraído de SettingsService
 * (fase 9 da refatoração). Sem conhecimento de histórico de auditoria ou jobs de apply — puramente
 * E/S de arquivo, incluindo o critério de mascaramento de chaves secretas (usado tanto na leitura
 * quanto no registro de histórico).
 */
@Slf4j
@Component
public class EnvFileStore {

    /** Caminho do .env dentro do container (mapeado via volume). */
    @Value("${app.settings.file-path:/opt/asteriskia/env/.env}")
    private String settingsFilePath;

    public static final String MASK_DISPLAY = "••••••••";

    /** Máximo de backups do .env a manter. */
    private static final int MAX_BACKUPS = 10;

    /**
     * Sufixos de chave cujo valor não deve ser exposto no GET. Achado de segurança: a allowlist
     * fixa anterior não cobria chaves novas (ex: RAMAL_*_PASSWORD, AGENTS_LLM_*_KEY) — um grupo de
     * acesso com só PERM_READ_telecom.settings via a credencial em texto claro. Critério por sufixo
     * cobre qualquer chave futura sem precisar lembrar de atualizar uma lista.
     */
    private static final Set<String> SECRET_SUFFIXES =
            Set.of("_PASSWORD", "_KEY", "_TOKEN", "_SECRET", "_CREDENTIAL");

    public static boolean isSecretKey(String key) {
        return SECRET_SUFFIXES.stream().anyMatch(key::endsWith);
    }

    public Path path() {
        return Path.of(settingsFilePath);
    }

    /** Lê o arquivo .env e retorna uma lista ordenada de entradas. */
    public List<EnvEntry> readEntries() throws IOException {
        Path path = path();
        if (!Files.exists(path)) {
            log.warn(
                    "Arquivo .env não encontrado em {}. Retornando lista vazia.", settingsFilePath);
            return List.of();
        }

        List<EnvEntry> entries = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                entries.add(EnvEntry.blank());
            } else if (trimmed.startsWith("#")) {
                entries.add(EnvEntry.comment(trimmed));
            } else {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    boolean secret = isSecretKey(key);
                    entries.add(EnvEntry.field(key, secret ? MASK_DISPLAY : value, secret));
                }
            }
        }
        return entries;
    }

    /** Lê o .env como Map<key, value> sem mascarar nada (uso interno). */
    public Map<String, String> readRaw() throws IOException {
        Path path = path();
        if (!Files.exists(path)) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.trim().startsWith("#")) {
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return map;
    }

    /**
     * Reescreve o arquivo .env preservando comentários e ordem originais, atualizando somente as
     * chaves presentes em {@code resolved} e appendando as que não existiam. Escrita atômica (write
     * -> rename).
     */
    public void rewrite(Map<String, String> resolved) throws IOException {
        Path path = path();
        List<String> lines =
                Files.exists(path)
                        ? Files.readAllLines(path, StandardCharsets.UTF_8)
                        : new ArrayList<>();

        List<String> output = new ArrayList<>();
        Set<String> written = new LinkedHashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                output.add(line);
            } else {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    if (resolved.containsKey(key)) {
                        output.add(key + "=" + resolved.get(key));
                        written.add(key);
                    } else {
                        output.add(line);
                    }
                } else {
                    output.add(line);
                }
            }
        }

        // Appenda chaves novas que não existiam no arquivo original
        resolved.forEach(
                (k, v) -> {
                    if (!written.contains(k)) {
                        output.add(k + "=" + v);
                    }
                });

        Files.createDirectories(path.getParent());

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
                tmp,
                String.join(System.lineSeparator(), output) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        log.info("Arquivo .env atualizado em {} ({} chaves)", settingsFilePath, resolved.size());
    }

    /**
     * Cria uma cópia do .env atual com sufixo timestamp. Mantém apenas os MAX_BACKUPS mais recentes
     * no diretório.
     */
    public void backup() {
        Path envPath = path();
        if (!Files.exists(envPath)) return;
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = envPath.resolveSibling(".env.bak-" + ts);
            Files.copy(envPath, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backup do .env criado: {}", backup.getFileName());
            pruneOldBackups(envPath.getParent());
        } catch (IOException e) {
            log.warn("Não foi possível criar backup do .env: {}", e.getMessage());
        }
    }

    /** Remove backups mais antigos se passar de MAX_BACKUPS. */
    private void pruneOldBackups(Path dir) {
        try {
            List<Path> backups;
            try (var stream = Files.list(dir)) {
                backups =
                        stream.filter(p -> p.getFileName().toString().startsWith(".env.bak-"))
                                .sorted(Comparator.reverseOrder())
                                .collect(Collectors.toList());
            }
            if (backups.size() > MAX_BACKUPS) {
                backups.subList(MAX_BACKUPS, backups.size())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ex) {
                                        log.warn(
                                                "Não foi possível remover backup antigo {}: {}",
                                                p,
                                                ex.getMessage());
                                    }
                                });
                log.info("Backups antigos removidos, mantendo os {} mais recentes", MAX_BACKUPS);
            }
        } catch (IOException e) {
            log.warn("Erro ao limpar backups antigos: {}", e.getMessage());
        }
    }
}
