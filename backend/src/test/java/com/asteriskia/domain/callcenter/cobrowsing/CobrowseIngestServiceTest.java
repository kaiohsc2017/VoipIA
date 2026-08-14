package com.asteriskia.domain.callcenter.cobrowsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre {@link CobrowseIngestService} (Fase 17b) — guardas de negócio (consentimento/sessão
 * encerrada/toggle do agente), teto acumulado (tamanho/tempo), e a garantia central de "nunca
 * lança" mesmo com falha real de I/O em disco. O teste de concorrência usa threads reais e um
 * diretório temporário real (sem mock de filesystem) para provar que o lock por sessão evita
 * corromper o arquivo gzip com apêndices simultâneos.
 */
@ExtendWith(MockitoExtension.class)
class CobrowseIngestServiceTest {

    @Mock
    private CcCobrowseSessionRepository cobrowseSessionRepository;
    @Mock
    private CcChatSessionRepository chatSessionRepository;

    private CobrowseEventSanitizer sanitizer;
    private ObjectMapper objectMapper;
    private CobrowseIngestService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sanitizer = new CobrowseEventSanitizer();
        objectMapper = new ObjectMapper();
        service = new CobrowseIngestService(cobrowseSessionRepository, chatSessionRepository, sanitizer, objectMapper);
        ReflectionTestUtils.setField(service, "basePath", tempDir.toString());
    }

    private CcCobrowseSession grantedSession(Long chatSessionId, LocalDateTime startedAt) {
        return CcCobrowseSession.builder()
                .id(1L)
                .chatSessionId(chatSessionId)
                .consentStatus("granted")
                .startedAt(startedAt)
                .sizeBytes(0L)
                .eventCount(0)
                .truncated(false)
                .build();
    }

    private CcChatSession openChatWithAgent(boolean cobrowseEnabled) {
        CcAgent agent = new CcAgent();
        agent.setId(10L);
        agent.setCobrowseEnabled(cobrowseEnabled);
        CcChatSession session = CcChatSession.builder().id(5L).assignedAgent(agent).build();
        return session;
    }

    private List<Map<String, Object>> sampleEvents(int count) {
        return Stream.generate(() -> Map.<String, Object>of("type", 3, "data", "evento de teste"))
                .limit(count)
                .toList();
    }

    @Test
    @DisplayName("rejeita sem sessão de cobrowse com consentimento granted (403, nunca chama chatSessionRepository)")
    void ingest_noGrantedConsent_rejects() {
        when(cobrowseSessionRepository.findByChatSessionId(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(5L, sampleEvents(1)))
                .isInstanceOf(ResponseStatusException.class);

        verify(chatSessionRepository, never()).findById(any());
        verify(cobrowseSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejeita quando a sessão de chat já foi encerrada")
    void ingest_closedChatSession_rejects() {
        when(cobrowseSessionRepository.findByChatSessionId(5L))
                .thenReturn(Optional.of(grantedSession(5L, LocalDateTime.now())));
        CcChatSession closed = openChatWithAgent(true);
        closed.setClosedAt(LocalDateTime.now());
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.ingest(5L, sampleEvents(1)))
                .isInstanceOf(ResponseStatusException.class);

        verify(cobrowseSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejeita quando o toggle do agente foi desligado depois do claim")
    void ingest_agentToggleOff_rejects() {
        when(cobrowseSessionRepository.findByChatSessionId(5L))
                .thenReturn(Optional.of(grantedSession(5L, LocalDateTime.now())));
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(openChatWithAgent(false)));

        assertThatThrownBy(() -> service.ingest(5L, sampleEvents(1)))
                .isInstanceOf(ResponseStatusException.class);

        verify(cobrowseSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("aplica truncated ao estourar o teto de tempo (60min), sem lançar exceção")
    void ingest_timeCapExceeded_marksTruncated() {
        CcCobrowseSession session = grantedSession(5L, LocalDateTime.now().minusMinutes(61));
        when(cobrowseSessionRepository.findByChatSessionId(5L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(openChatWithAgent(true)));
        when(cobrowseSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(5L, sampleEvents(1));

        assertThat(session.getTruncated()).isTrue();
        verify(cobrowseSessionRepository).save(session);
    }

    @Test
    @DisplayName("aplica truncated ao estourar o teto de tamanho (10MB), sem lançar exceção")
    void ingest_sizeCapExceeded_marksTruncated() {
        CcCobrowseSession session = grantedSession(5L, LocalDateTime.now());
        session.setSizeBytes(CobrowseIngestService.MAX_SESSION_BYTES); // já no teto
        when(cobrowseSessionRepository.findByChatSessionId(5L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(openChatWithAgent(true)));
        when(cobrowseSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(5L, sampleEvents(1));

        assertThat(session.getTruncated()).isTrue();
    }

    @Test
    @DisplayName("sessão já truncada ignora novos lotes silenciosamente, sem tentar gravar em disco de novo")
    void ingest_alreadyTruncated_ignoresSilently() {
        CcCobrowseSession session = grantedSession(5L, LocalDateTime.now());
        session.setTruncated(true);
        when(cobrowseSessionRepository.findByChatSessionId(5L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(openChatWithAgent(true)));

        service.ingest(5L, sampleEvents(1));

        verify(cobrowseSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("grava com sucesso, atualiza contadores e filePath, arquivo é gzip válido e legível")
    void ingest_success_writesFileAndUpdatesCounters() throws IOException {
        CcCobrowseSession session = grantedSession(5L, LocalDateTime.now());
        when(cobrowseSessionRepository.findByChatSessionId(5L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(openChatWithAgent(true)));
        when(cobrowseSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(5L, sampleEvents(2));

        assertThat(session.getEventCount()).isEqualTo(2);
        assertThat(session.getSizeBytes()).isGreaterThan(0L);
        assertThat(session.getFilePath()).isNotNull();
        assertThat(session.getLastEventAt()).isNotNull();

        byte[] decompressed = readAllGzipMembers(Path.of(session.getFilePath()));
        String content = new String(decompressed, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content.lines().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("nunca propaga IOException — falha real de disco (diretório bloqueado por arquivo) é logada e não lança")
    void ingest_ioFailure_neverThrows() throws IOException {
        // Cria um ARQUIVO regular no lugar onde o serviço tentaria criar um diretório de ano —
        // Files.createDirectories falha com FileAlreadyExistsException (subtipo de IOException).
        CcCobrowseSession session = grantedSession(5L, LocalDateTime.now());
        String year = String.valueOf(LocalDateTime.now().getYear());
        Files.writeString(tempDir.resolve(year), "bloqueando o diretório de propósito");

        when(cobrowseSessionRepository.findByChatSessionId(5L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(openChatWithAgent(true)));

        service.ingest(5L, sampleEvents(1));

        // Nunca lançou (chegamos até aqui) e os contadores não avançaram, pois a gravação falhou.
        assertThat(session.getEventCount()).isEqualTo(0);
        verify(cobrowseSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("dois lotes concorrentes da mesma sessão não corrompem o arquivo gzip")
    void ingest_concurrentBatches_doNotCorruptFile() throws Exception {
        CcCobrowseSession session = grantedSession(5L, LocalDateTime.now());
        when(cobrowseSessionRepository.findByChatSessionId(5L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.findById(5L)).thenReturn(Optional.of(openChatWithAgent(true)));
        when(cobrowseSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int threads = 8;
        int eventsPerBatch = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    service.ingest(5L, sampleEvents(eventsPerBatch));
                });
            }
            ready.await(2, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        byte[] decompressed = readAllGzipMembers(Path.of(session.getFilePath()));
        String content = new String(decompressed, java.nio.charset.StandardCharsets.UTF_8);
        long lineCount = content.lines().count();
        assertThat(lineCount).isEqualTo((long) threads * eventsPerBatch);
        assertThat(session.getEventCount()).isEqualTo(threads * eventsPerBatch);
    }

    /** GZIPInputStream decodifica membros gzip concatenados automaticamente (JDK 7+) — lê tudo
     * de uma vez só para validar que o arquivo inteiro é um gzip íntegro e completo. */
    private byte[] readAllGzipMembers(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             GZIPInputStream gzip = new GZIPInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toByteArray();
        }
    }
}
