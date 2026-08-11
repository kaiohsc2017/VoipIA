package com.asteriskia.domain.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConfigService — Leitura de configurações dinâmicas do banco de dados.
 *
 * Cache em memória com TTL de 60 segundos por chave:
 *   - Evita round-trip ao banco em toda requisição de integração
 *   - Garante que alterações na tela reflitam em no máximo 60s sem restart
 *
 * Fallback chain: banco → variável de ambiente → valor padrão
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final SystemConfigRepository repository;

    // Cache: key → (value, expiresAt)
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    private record CachedEntry(String value, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    // ─── Leitura ──────────────────────────────────────────────────────────────

    /**
     * Retorna o valor da configuração.
     * Ordem: cache → banco → env var → defaultValue
     */
    public String get(String key, String defaultValue) {
        CachedEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        // Cache miss ou expirado — busca no banco
        Optional<SystemConfig> record = repository.findById(key);
        String value;

        if (record.isPresent() && !record.get().getValue().isBlank()) {
            value = record.get().getValue();
        } else {
            // Fallback para variável de ambiente
            String envVal = System.getenv(key);
            value = (envVal != null && !envVal.isBlank()) ? envVal : defaultValue;
            if (record.isEmpty()) {
                log.debug("ConfigService: chave '{}' não encontrada no banco — usando fallback", key);
            }
        }

        cache.put(key, new CachedEntry(value, Instant.now().plus(CACHE_TTL)));
        return value;
    }

    /** Atalho sem valor padrão — retorna string vazia. */
    public String get(String key) {
        return get(key, "");
    }

    /** Retorna int, com fallback para defaultValue. */
    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            log.warn("ConfigService: chave '{}' não é um inteiro válido — usando {}", key, defaultValue);
            return defaultValue;
        }
    }

    // ─── Escrita ──────────────────────────────────────────────────────────────

    /**
     * Salva ou atualiza uma configuração e invalida o cache da chave.
     */
    @Transactional
    public void set(String key, String value, String updatedBy) {
        SystemConfig config = repository.findById(key)
                .orElseGet(() -> SystemConfig.builder().key(key).isSecret(false).build());
        config.setValue(value);
        config.setUpdatedBy(updatedBy != null ? updatedBy : "system");
        repository.save(config);
        cache.remove(key); // Invalida imediatamente
        log.info("ConfigService: chave '{}' atualizada por '{}'", key, updatedBy);
    }

    /**
     * Salva múltiplas chaves de uma vez (ex: ao salvar uma seção inteira).
     * Invalida o cache de todas as chaves afetadas.
     */
    @Transactional
    public void setAll(Map<String, String> updates, String updatedBy) {
        updates.forEach((key, value) -> set(key, value, updatedBy));
    }

    /**
     * Invalida o cache de uma chave específica (chamado externamente se necessário).
     */
    public void invalidate(String key) {
        cache.remove(key);
    }

    /** Invalida todo o cache (ex: após um apply em massa). */
    public void invalidateAll() {
        cache.clear();
        log.info("ConfigService: cache invalidado por completo");
    }

    // ─── Admin ────────────────────────────────────────────────────────────────

    /** Retorna todas as configs do banco (para a tela de Settings). */
    @Transactional(readOnly = true)
    public List<SystemConfig> findAll() {
        return repository.findAll();
    }
}
