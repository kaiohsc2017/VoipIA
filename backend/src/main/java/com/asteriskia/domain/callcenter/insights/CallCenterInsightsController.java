package com.asteriskia.domain.callcenter.insights;

import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.InsightProcessingItem;
import com.asteriskia.domain.insights.InsightsDashboardSummary;
import com.asteriskia.domain.insights.InsightsDetailResponse;
import com.asteriskia.domain.insights.InsightsFilter;
import com.asteriskia.domain.insights.InsightsListItem;
import com.asteriskia.domain.insights.InsightsProcessingFilter;
import com.asteriskia.domain.insights.InsightsQueryService;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * CallCenterInsightsController — busca, detalhe, streaming de áudio, dashboard e
 * processamento das gravações do Call Center (Fase 8 do plano
 * modulo-callcenter-omnicanal.plan.md) — mesmo pipeline de IA do módulo Insights
 * (Verint), reaproveitado via {@link InsightsQueryService} com {@code source="callcenter"}.
 *
 * Diferente do fluxo Verint/upload, não há distinção de posse (as gravações do Call
 * Center pertencem à operação, não a um supervisor individual) — a permissão de aba
 * ({@code callcenter.insights.*}) já basta.
 *
 * <p>GAP CONHECIDO (achado de revisão de segurança da Fase 8): diferente do resto do
 * Call Center — {@code CallCenterRecordingService.findRecordings} já restringe por
 * {@link com.asteriskia.domain.masterdata.BusinessUnitContext} — este controller não
 * aplica nenhum filtro de BU; {@link InsightsQueryService} só filtra por {@code source}.
 * Um usuário com {@code PERM_READ_callcenter.insights.*} vê transcrição/áudio de todas as
 * BUs, não só a sua. É o mesmo padrão já aceito no módulo Insights (Verint), que também
 * nunca teve escopo de BU — mesmo gap documentado para Alertas Zabbix
 * ({@code AlertService}). Resolver exigiria join de {@code call_audio_files.ccRecordingId}
 * até {@code cc_recordings.businessUnit} em {@link InsightsSpecifications}; fora do escopo
 * desta fatia.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/callcenter/insights")
@RequiredArgsConstructor
public class CallCenterInsightsController {

    private static final String SOURCE = "callcenter";

    private final InsightsQueryService queryService;

    @Value("${app.callcenter.recording-path:/opt/AsteriskIA/media/gravacao}")
    private String recordingBasePath;

    @GetMapping("/calls")
    public ResponseEntity<Page<InsightsListItem>> listCalls(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String phrase,
            @RequestParam(required = false) String toneCliente,
            @RequestParam(required = false) String toneAtendente,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String criticidade,
            @RequestParam(required = false) String findingType,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String skill) {
        boolean isAdmin = isAdmin();
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "callStarttime"));
        InsightsFilter filter = new InsightsFilter(
                id,
                dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                text, phrase, toneCliente, toneAtendente, categoria, criticidade, findingType,
                agentName, "inbound", skill, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
        return ResponseEntity.ok(queryService.search(filter, pageable, isAdmin, SOURCE));
    }

    @GetMapping("/calls/{id}")
    public ResponseEntity<InsightsDetailResponse> getCall(@PathVariable Long id) {
        CallAudioFile rawAudioFile = queryService.findAudioFileById(id);
        if (!SOURCE.equals(rawAudioFile.getSource())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        InsightsDetailResponse detail = queryService.detail(id, isAdmin());
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<InsightsDashboardSummary> dashboard() {
        return ResponseEntity.ok(queryService.dashboard(SOURCE));
    }

    @GetMapping("/processing")
    public ResponseEntity<Page<InsightProcessingItem>> listProcessing(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "ingestedAt"));
        InsightsProcessingFilter filter = new InsightsProcessingFilter(
                status,
                dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                fileName);
        return ResponseEntity.ok(queryService.findProcessing(filter, pageable, SOURCE));
    }

    /**
     * Streaming do .wav original em /opt/telecom/gravacao (subpastas yyyy/mm/dd, mesma
     * defesa de path traversal de CallCenterRecordingService.resolveAudioFile) —
     * transcodificado via ffmpeg (o áudio original já é PCM 8kHz mono do MixMonitor, mas
     * passa pelo mesmo pipeline do Verint por uniformidade e para normalizar o
     * Content-Type).
     */
    @GetMapping("/calls/{id}/audio")
    public ResponseEntity<StreamingResponseBody> getAudio(@PathVariable Long id) {
        CallAudioFile audioFile = queryService.findAudioFileById(id);
        if (!SOURCE.equals(audioFile.getSource())
                || audioFile.getWavPath() == null
                || audioFile.getWavPath().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        File resolved = resolveWithinBase(audioFile.getWavPath());
        if (resolved == null || !resolved.exists() || !resolved.canRead()) {
            log.warn("Arquivo de áudio não encontrado para insight de Call Center id={}", id);
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody body = outputStream -> {
            Process ffmpeg = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-i", resolved.getAbsolutePath(),
                    "-f", "wav", "-ar", "8000", "-ac", "1",
                    "-")
                    .redirectErrorStream(false)
                    .start();
            try (var ffmpegStdout = ffmpeg.getInputStream()) {
                ffmpegStdout.transferTo(outputStream);
            } finally {
                ffmpeg.destroy();
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + audioFile.getCallRef() + ".wav\"")
                .contentType(MediaType.valueOf("audio/wav"))
                .body(body);
    }

    /** Nunca abre o caminho gravado no banco diretamente — resolve o subcaminho relativo
     * (yyyy/mm/dd/arquivo.wav) dentro do diretório base configurado, validando o caminho
     * canônico contra escape (mesmo padrão de CallCenterRecordingService.resolveAudioFile
     * e InsightsController.resolveWithinBase). */
    private File resolveWithinBase(String storedPath) {
        try {
            File base = new File(recordingBasePath).getCanonicalFile();
            File stored = new File(storedPath).getCanonicalFile();
            String basePath = base.getPath() + File.separator;
            if (!stored.getPath().startsWith(basePath)) {
                return null;
            }
            return stored;
        } catch (IOException e) {
            log.warn("Erro ao resolver caminho de áudio do Call Center: {}", e.getMessage());
            return null;
        }
    }

    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
