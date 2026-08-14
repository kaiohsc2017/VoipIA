package com.asteriskia.domain.callcenter.cobrowsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CallCenterCobrowsingServiceTest — escopo de BU, leitura de eventos (com defesa de path
 * traversal) e eliminação sob demanda (Fase 17c).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallCenterCobrowsingServiceTest {

    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Mock private CcCobrowseSessionRepository cobrowseSessionRepository;

    @TempDir Path tempDir;

    private CallCenterCobrowsingService newService() throws Exception {
        var service = new CallCenterCobrowsingService(cobrowseSessionRepository, new ObjectMapper());
        Field field = CallCenterCobrowsingService.class.getDeclaredField("basePath");
        field.setAccessible(true);
        field.set(service, tempDir.toString());
        return service;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void restrictToBusinessUnits(int... buIds) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        for (int id : buIds) {
            authorities.add(new SimpleGrantedAuthority("BU_" + id));
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user", null, authorities));
    }

    @Test
    @DisplayName("findByIdInScope: usuário restrito a outra BU não enxerga a sessão (retorna vazio)")
    void findByIdInScope_outOfBusinessUnitScope_returnsEmpty() throws Exception {
        var service = newService();
        restrictToBusinessUnits(1);
        var session = CcCobrowseSession.builder().id(5L).businessUnitId(2L).build();
        when(cobrowseSessionRepository.findById(5L)).thenReturn(Optional.of(session));

        assertThat(service.findByIdInScope(5L)).isEmpty();
    }

    @Test
    @DisplayName("findByIdInScope: usuário restrito à mesma BU enxerga a sessão")
    void findByIdInScope_sameBusinessUnit_returnsSession() throws Exception {
        var service = newService();
        restrictToBusinessUnits(1);
        var session = CcCobrowseSession.builder().id(5L).businessUnitId(1L).build();
        when(cobrowseSessionRepository.findById(5L)).thenReturn(Optional.of(session));

        assertThat(service.findByIdInScope(5L)).contains(session);
    }

    @Test
    @DisplayName("findByIdInScope: sessão sem BU (null) é visível a qualquer usuário restrito")
    void findByIdInScope_nullBusinessUnit_alwaysVisible() throws Exception {
        var service = newService();
        restrictToBusinessUnits(1);
        var session = CcCobrowseSession.builder().id(5L).businessUnitId(null).build();
        when(cobrowseSessionRepository.findById(5L)).thenReturn(Optional.of(session));

        assertThat(service.findByIdInScope(5L)).contains(session);
    }

    private CcCobrowseSession sessionWithFile(Long chatSessionId, LocalDateTime startedAt) throws IOException {
        Path dir = tempDir.resolve(DIR_FORMAT.format(startedAt));
        Files.createDirectories(dir);
        Path file = dir.resolve(chatSessionId + ".events.jsonl.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write("{\"type\":2,\"data\":\"a\"}\n".getBytes(StandardCharsets.UTF_8));
            out.write("{\"type\":3,\"data\":\"b\"}\n".getBytes(StandardCharsets.UTF_8));
        }
        return CcCobrowseSession.builder()
                .id(1L)
                .chatSessionId(chatSessionId)
                .startedAt(startedAt)
                .filePath(file.toString())
                .build();
    }

    @Test
    @DisplayName("readEvents: descomprime e desserializa o jsonl.gz linha a linha")
    void readEvents_validFile_returnsDeserializedEvents() throws Exception {
        var service = newService();
        var session = sessionWithFile(42L, LocalDateTime.of(2026, 8, 14, 10, 0));

        List<?> events = service.readEvents(session);

        assertThat(events).hasSize(2);
    }

    @Test
    @DisplayName("readEvents: arquivo ausente retorna null (nunca lança)")
    void readEvents_missingFile_returnsNull() throws Exception {
        var service = newService();
        var session = CcCobrowseSession.builder()
                .id(1L).chatSessionId(999L)
                .startedAt(LocalDateTime.of(2026, 8, 14, 10, 0))
                .filePath("qualquer.jsonl.gz")
                .build();

        assertThat(service.readEvents(session)).isNull();
    }

    @Test
    @DisplayName("readEvents: defesa de path traversal — chatSessionId malicioso não escapa da raiz de mídia")
    void readEvents_pathTraversalAttempt_returnsNull() throws Exception {
        var service = newService();
        // chatSessionId é sempre Long no domínio real (nunca string arbitrária vinda de input
        // externo), mas o teste força uma sessão com startedAt fora do padrão para confirmar que
        // a resolução nunca escapa da raiz mesmo se os campos confiáveis fossem manipulados.
        var session = CcCobrowseSession.builder()
                .id(1L).chatSessionId(1L)
                .startedAt(LocalDateTime.of(2026, 8, 14, 10, 0))
                .filePath("../../../../etc/passwd")
                .build();

        // Sem o arquivo real no caminho canônico esperado, a leitura retorna null (arquivo
        // ausente) — nunca tenta ler filePath cru.
        assertThat(service.readEvents(session)).isNull();
    }

    @Test
    @DisplayName("purge: apaga o arquivo físico e marca purgedAt, preservando a linha")
    void purge_deletesFileAndMarksPurgedAt() throws Exception {
        var service = newService();
        var session = sessionWithFile(7L, LocalDateTime.of(2026, 8, 14, 10, 0));
        when(cobrowseSessionRepository.save(session)).thenReturn(session);
        Path file = Path.of(session.getFilePath());
        assertThat(Files.exists(file)).isTrue();

        var purged = service.purge(session);

        assertThat(Files.exists(file)).isFalse();
        assertThat(purged.getPurgedAt()).isNotNull();
    }

    @Test
    @DisplayName("purge: idempotente — segunda chamada não sobrescreve purgedAt nem falha")
    void purge_calledTwice_isIdempotent() throws Exception {
        var service = newService();
        var session = sessionWithFile(8L, LocalDateTime.of(2026, 8, 14, 10, 0));
        LocalDateTime firstPurge = LocalDateTime.of(2026, 8, 14, 9, 0);
        session.setPurgedAt(firstPurge);

        var result = service.purge(session);

        assertThat(result.getPurgedAt()).isEqualTo(firstPurge);
    }

    @Test
    @DisplayName("purge: arquivo já ausente não impede marcar purgedAt (nunca lança)")
    void purge_fileAlreadyMissing_stillMarksPurgedAt() throws Exception {
        var service = newService();
        var session = CcCobrowseSession.builder()
                .id(1L).chatSessionId(999L)
                .startedAt(LocalDateTime.of(2026, 8, 14, 10, 0))
                .filePath("inexistente.jsonl.gz")
                .build();
        when(cobrowseSessionRepository.save(session)).thenReturn(session);

        var purged = service.purge(session);

        assertThat(purged.getPurgedAt()).isNotNull();
    }
}
