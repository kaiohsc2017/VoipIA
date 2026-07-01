package com.asteriskia.domain.ura;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UraRoutingService — Correlaciona o UUID de uma chamada (AudioSocket, que só
 * transmite um UUID binário) com a URA que a originou.
 *
 * O dialplan do Asterisk registra essa correlação (via CURL) logo após gerar
 * o UUID e antes de conectar ao AudioSocket; o ai-agent consulta em seguida.
 * Armazenamento em memória — é uma correlação de vida curta (duração do setup
 * da chamada), não precisa de persistência em banco.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UraRoutingService {

    private static final long ENTRY_TTL_SECONDS = 300;

    private record Entry(Integer uraId, Instant registeredAt) {}

    private final Map<String, Entry> uuidToUra = new ConcurrentHashMap<>();
    private final UraRepository uraRepository;

    public void register(String callUuid, String extension) {
        cleanupStale();
        Ura ura = uraRepository.findByExtension(extension).orElse(null);
        if (ura == null) {
            log.warn("Nenhuma URA cadastrada para o ramal {} (uuid={}) — chamada usará a URA padrão", extension, callUuid);
            return;
        }
        uuidToUra.put(callUuid, new Entry(ura.getId(), Instant.now()));
        log.info("UUID {} registrado para URA id={} (ramal {})", callUuid, ura.getId(), extension);
    }

    /** Consome (remove) a correlação — cada chamada só precisa ser resolvida uma vez. */
    public Integer resolve(String callUuid) {
        Entry entry = uuidToUra.remove(callUuid);
        return entry != null ? entry.uraId() : null;
    }

    private void cleanupStale() {
        Instant cutoff = Instant.now().minusSeconds(ENTRY_TTL_SECONDS);
        uuidToUra.values().removeIf(e -> e.registeredAt().isBefore(cutoff));
    }
}
