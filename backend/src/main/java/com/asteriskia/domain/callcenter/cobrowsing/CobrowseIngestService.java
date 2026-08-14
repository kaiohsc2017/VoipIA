package com.asteriskia.domain.callcenter.cobrowsing;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CobrowseIngestService — ingestão dos lotes de eventos rrweb do co-browsing gravado do chat
 * (Fase 17, sub-fase 17b). Grava em disco no molde de {@code ChatTranscriptExportService}
 * ({@code media/chat/YYYY/MM/DD/<chatSessionId>.events.jsonl.gz}), nunca no banco (mesmo padrão
 * de mídia + ponteiro já usado em todo o domínio {@code callcenter}).
 *
 * <p><b>Guardas, na ordem do plano (§5.3):</b> (a) sessão de cobrowse existe e está
 * {@code consentStatus=granted}; (b) chat ainda não encerrado; (c) toggle do agente ainda ligado
 * (revalidado a cada lote, nunca confia em estado antigo). As três violações respondem o mesmo
 * 403 genérico — nunca revela qual delas falhou a quem tenta um id arbitrário (mesmo padrão de
 * {@link CobrowseConsentService}). Rate limit (d) e teto de corpo (e) são responsabilidade do
 * controller, antes de chamar este serviço.
 *
 * <p><b>Teto acumulado (f):</b> 10MB comprimido OU 60 minutos de captura desde
 * {@code started_at} — ao estourar, marca {@code truncated=true} e para de aceitar novos eventos
 * silenciosamente (sempre responde 204, nunca quebra a conversa do cliente).
 *
 * <p><b>Nunca lança:</b> falha de I/O ao gravar em disco é logada e a requisição retorna
 * normalmente — o próximo lote tenta de novo; eventos do lote que falhou não incrementam os
 * contadores da sessão (evita afirmar tamanho/contagem que não correspondem ao arquivo real).
 *
 * <p><b>Concorrência:</b> lock em memória por {@code chatSessionId} — {@link GZIPOutputStream}
 * não é seguro para apêndice concorrente no mesmo arquivo. Cada lote é comprimido isoladamente
 * num buffer em memória (obtendo o tamanho exato do gzip member antes de decidir se cabe no
 * teto) e depois seus bytes já prontos são anexados ao arquivo em modo append — o formato gzip
 * (RFC 1952) permite concatenar múltiplos "members" no mesmo arquivo; qualquer leitor padrão
 * (inclusive {@link java.util.zip.GZIPInputStream}, que decodifica membros concatenados
 * automaticamente desde o JDK 7) reconstrói o conteúdo completo na leitura.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CobrowseIngestService {

    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    // Fase 17b, item 4(f) do plano: 10MB comprimido OU 60 minutos de captura, o que vier primeiro.
    static final long MAX_SESSION_BYTES = 10L * 1024 * 1024;
    static final long MAX_CAPTURE_MINUTES = 60L;

    private final CcCobrowseSessionRepository cobrowseSessionRepository;
    private final CcChatSessionRepository chatSessionRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.callcenter.chat-transcript-path:/opt/AsteriskIA/media/chat}")
    private String basePath;

    // Um lock por sessão de chat — nunca removido do mapa (mesmo trade-off já aceito em
    // PublicChatRateLimiter antes do expurgo periódico: pegada de memória pequena e por chave,
    // não um vazamento sem limite).
    private final ConcurrentHashMap<Long, Object> fileLocks = new ConcurrentHashMap<>();

    // Fase 17b — achado desta revisão (mesma classe já corrigida na Fase 21,
    // CallCenterSurveyRunner.run/CallCenterNpsTranscriptionScheduler.processOne): NÃO anotar
    // este método com @Transactional. O corpo faz compressão gzip e I/O de disco (append em
    // media/chat/**) dentro do lock por sessão — em carga (múltiplas sessões de chat com
    // cobrowsing simultâneas, lotes a cada 5s), isso prenderia uma conexão do pool de banco
    // pelo tempo do I/O bloqueante, não só pelo tempo de trabalho de banco. Cada leitura
    // (findByChatSessionId/findById) e cada save() já é transacional por conta própria via
    // Spring Data — CcChatSession.assignedAgent é FetchType.EAGER, então acessá-lo fora de uma
    // transação abrangente não lança LazyInitializationException.
    public void ingest(Long chatSessionId, List<Map<String, Object>> events) {
        CcCobrowseSession cobrowseSession = requireGrantedSession(chatSessionId);
        requireOpenChatWithCobrowseEnabled(chatSessionId, cobrowseSession);

        if (Boolean.TRUE.equals(cobrowseSession.getTruncated())) {
            // Já truncado por um lote anterior — ignora silenciosamente, sem reabrir a captura.
            return;
        }
        if (events == null || events.isEmpty()) {
            return;
        }

        byte[] compressed = compress(chatSessionId, events);
        if (compressed == null) {
            // Falha ao serializar/comprimir — já logada em compress(); nada a persistir.
            return;
        }

        // Todo o trecho abaixo (checagem de teto + gravação em disco + atualização de
        // contadores) roda sob o mesmo lock por sessão — não só a escrita em disco. Sem isso,
        // dois lotes concorrentes da mesma sessão poderiam ler o mesmo sizeBytes/eventCount
        // "antigo" e um dos incrementos se perderia (mesmo problema clássico de "leitura+escrita
        // não-atômica" de um contador compartilhado, independente do arquivo em disco estar
        // correto ou não).
        Object lock = fileLocks.computeIfAbsent(chatSessionId, k -> new Object());
        synchronized (lock) {
            long elapsedMinutes = Duration.between(cobrowseSession.getStartedAt(), LocalDateTime.now()).toMinutes();
            long projectedSize = cobrowseSession.getSizeBytes() + compressed.length;
            if (elapsedMinutes >= MAX_CAPTURE_MINUTES || projectedSize > MAX_SESSION_BYTES) {
                cobrowseSession.setTruncated(true);
                cobrowseSessionRepository.save(cobrowseSession);
                log.info("Co-browsing truncado por teto de tamanho/tempo: chatSessionId={} elapsedMinutes={} projectedSize={}",
                        chatSessionId, elapsedMinutes, projectedSize);
                return;
            }

            Path filePath = appendToDisk(cobrowseSession, compressed);
            if (filePath == null) {
                // Falha de I/O já logada em appendToDisk() — nunca propaga, contadores não avançam.
                return;
            }

            cobrowseSession.setSizeBytes(projectedSize);
            cobrowseSession.setEventCount(cobrowseSession.getEventCount() + events.size());
            cobrowseSession.setLastEventAt(LocalDateTime.now());
            cobrowseSession.setFilePath(filePath.toString());
            cobrowseSessionRepository.save(cobrowseSession);
        }
    }

    private CcCobrowseSession requireGrantedSession(Long chatSessionId) {
        return cobrowseSessionRepository.findByChatSessionId(chatSessionId)
                .filter(s -> "granted".equals(s.getConsentStatus()))
                .orElseThrow(this::forbidden);
    }

    private void requireOpenChatWithCobrowseEnabled(Long chatSessionId, CcCobrowseSession cobrowseSession) {
        CcChatSession chatSession = chatSessionRepository.findById(chatSessionId).orElseThrow(this::forbidden);
        if (chatSession.getClosedAt() != null) {
            throw forbidden();
        }
        CcAgent agent = chatSession.getAssignedAgent();
        if (agent == null || !Boolean.TRUE.equals(agent.getCobrowseEnabled())) {
            throw forbidden();
        }
    }

    private ResponseStatusException forbidden() {
        // 403 genérico e uniforme para as 3 guardas (a/b/c do plano) — nunca revela qual delas
        // falhou, mesmo padrão de silêncio total já usado em CobrowseConsentService/endpoint de
        // consentimento (a diferença é 404 lá porque ali a ausência é o único estado possível;
        // aqui a sessão pode existir mas estar em qualquer um dos três estados inválidos).
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Captura de co-browsing não disponível para esta conversa.");
    }

    /** Comprime o lote num buffer em memória — nunca lança; falha vira log + null. */
    private byte[] compress(Long chatSessionId, List<Map<String, Object>> events) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                for (Map<String, Object> event : events) {
                    String line = objectMapper.writeValueAsString(event);
                    gzip.write(line.getBytes(StandardCharsets.UTF_8));
                    gzip.write('\n');
                }
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            log.error("Erro ao serializar/comprimir lote de co-browsing: chatSessionId={}: {}",
                    chatSessionId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Anexa o gzip member já pronto ao arquivo da sessão. Chamado sempre sob o lock por sessão
     * adquirido em {@link #ingest} — não adquire lock por conta própria, para a checagem de
     * teto + gravação + atualização de contadores serem uma única seção crítica, nunca duas.
     * Nunca lança: qualquer {@link IOException} é logada e o método retorna {@code null}.
     */
    private Path appendToDisk(CcCobrowseSession cobrowseSession, byte[] compressedChunk) {
        try {
            Path path = resolveEventsFile(cobrowseSession);
            try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(compressedChunk);
            }
            return path;
        } catch (IOException e) {
            log.error("Erro ao gravar lote de co-browsing em disco: chatSessionId={}: {}",
                    cobrowseSession.getChatSessionId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Resolve {@code media/chat/YYYY/MM/DD/<chatSessionId>.events.jsonl.gz}, com defesa de path
     * traversal por canonicalização — {@code chatSessionId} já é um {@code Long} (Spring rejeita
     * qualquer valor não numérico no path variable antes de chegar aqui), mas a checagem abaixo é
     * uma segunda camada, mesmo padrão de {@code CallCenterRecordingService#resolveAudioFile}.
     */
    private Path resolveEventsFile(CcCobrowseSession cobrowseSession) throws IOException {
        Path base = Path.of(basePath).toAbsolutePath().normalize();
        Path dir = base.resolve(DIR_FORMAT.format(cobrowseSession.getStartedAt()));
        Path file = dir.resolve(cobrowseSession.getChatSessionId() + ".events.jsonl.gz").normalize();
        if (!file.startsWith(base)) {
            throw new IOException("Caminho de arquivo de co-browsing fora da raiz de mídia esperada.");
        }
        Files.createDirectories(dir);
        return file;
    }
}
