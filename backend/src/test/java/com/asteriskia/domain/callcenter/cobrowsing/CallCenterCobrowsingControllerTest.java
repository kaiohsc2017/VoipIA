package com.asteriskia.domain.callcenter.cobrowsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * CallCenterCobrowsingControllerTest — 404 nunca 403 (id inexistente/fora de BU/sem
 * consentimento/sem arquivo), auditoria de reprodução, e o comportamento de eliminação sob
 * demanda (Fase 17c). RBAC (403 sem token/sem permissão) é responsabilidade do SecurityConfig,
 * fora do escopo de um teste unitário de controller — coberto pela matriz de matchers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallCenterCobrowsingControllerTest {

    @Mock private CallCenterCobrowsingService service;
    @Mock private CallCenterCobrowseRetentionService retentionService;
    @Mock private AuditService auditService;
    @Mock private HttpServletRequest request;
    @Mock private org.springframework.security.core.Authentication authentication;

    private CallCenterCobrowsingController controller;

    @BeforeEach
    void setUp() {
        controller = new CallCenterCobrowsingController(service, retentionService, auditService);
    }

    private CcCobrowseSession session(String consentStatus, String filePath) {
        return CcCobrowseSession.builder()
                .id(5L)
                .chatSessionId(10L)
                .consentStatus(consentStatus)
                .filePath(filePath)
                .startedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("events: 404 quando o id não existe (nunca 403)")
    void events_idNotFound_returns404() {
        when(service.findByIdInScope(5L)).thenReturn(Optional.empty());

        var result = controller.events(5L, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(auditService, never()).log(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("events: 404 quando consentStatus não é granted (revogado/negado/pendente)")
    void events_consentNotGranted_returns404() {
        when(service.findByIdInScope(5L)).thenReturn(Optional.of(session("revoked", "algum/caminho.jsonl.gz")));

        var result = controller.events(5L, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).readEvents(any());
    }

    @Test
    @DisplayName("events: 404 quando filePath é nulo (nunca houve captura)")
    void events_nullFilePath_returns404() {
        when(service.findByIdInScope(5L)).thenReturn(Optional.of(session("granted", null)));

        var result = controller.events(5L, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).readEvents(any());
    }

    @Test
    @DisplayName("events: 404 quando o arquivo físico está ausente/ilegível (readEvents retorna null)")
    void events_fileMissing_returns404() {
        var session = session("granted", "algum/caminho.jsonl.gz");
        when(service.findByIdInScope(5L)).thenReturn(Optional.of(session));
        when(service.readEvents(session)).thenReturn(null);

        var result = controller.events(5L, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(auditService, never()).log(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("events: 200 com consentimento granted e arquivo válido — audita a reprodução")
    void events_valid_returns200AndAudits() {
        var session = session("granted", "algum/caminho.jsonl.gz");
        when(service.findByIdInScope(5L)).thenReturn(Optional.of(session));
        when(service.readEvents(session)).thenReturn(List.of());

        var result = controller.events(5L, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(auditService).log(request, "callcenter.cobrowsing.play", "Sessão de co-browsing id=5 reproduzida", true);
    }

    @Test
    @DisplayName("delete: 404 quando o id não existe/fora de escopo de BU (nunca 403)")
    void delete_idNotFound_returns404() {
        when(service.findByIdInScope(5L)).thenReturn(Optional.empty());

        var result = controller.delete(5L, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).purge(any());
    }

    @Test
    @DisplayName("delete: 204 e audita a eliminação sob demanda")
    void delete_valid_returns204AndAudits() {
        var session = session("granted", "algum/caminho.jsonl.gz");
        when(service.findByIdInScope(5L)).thenReturn(Optional.of(session));
        when(service.purge(session)).thenReturn(session);

        var result = controller.delete(5L, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).purge(session);
        verify(auditService).log(request, "callcenter.cobrowsing.purge", "Sessão de co-browsing id=5 eliminada sob demanda", true);
    }

    @Test
    @DisplayName("getRetentionConfig: delega ao CallCenterCobrowseRetentionService")
    void getRetentionConfig_delegatesToService() {
        var view = new CobrowseRetentionConfigView(1826, null, null);
        when(retentionService.getConfig()).thenReturn(view);

        var result = controller.getRetentionConfig();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(view);
    }

    @Test
    @DisplayName("updateRetentionConfig: repassa retentionDays e o nome do usuário autenticado")
    void updateRetentionConfig_delegatesToService() {
        var request2 = new CobrowseRetentionConfigRequest(365);
        var view = new CobrowseRetentionConfigView(365, null, null);
        when(authentication.getName()).thenReturn("admin@example.com");
        when(retentionService.updateConfig(request2, "admin@example.com")).thenReturn(view);

        var result = controller.updateRetentionConfig(request2, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(view);
    }

    @Test
    @DisplayName("runRetentionNow: dispara o expurgo manual")
    void runRetentionNow_delegatesToService() {
        var runResult = new CobrowseRetentionRunResult(3);
        when(retentionService.purgeExpired()).thenReturn(runResult);

        var result = controller.runRetentionNow();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(runResult);
    }
}
