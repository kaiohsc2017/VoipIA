package com.asteriskia.domain.insights;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * InsightsController — busca, detalhe, streaming de áudio e dashboard da tela Insights.
 *
 * Módulo apartado do domínio Asterisk (call_records/uras) — dados vêm do call
 * center corporativo Verint, processados pelo serviço asteriskia-insights.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
public class InsightsController {

    private final InsightsQueryService queryService;
    private final InsightsCostService costService;

    @Value("${app.insights.audio-path:/opt/audio}")
    private String insightsAudioPath;

    // Pasta separada dos uploads do portal do supervisor (Fase 3 do Quality Management,
    // V40) — nunca subpasta de insightsAudioPath, decisão deliberada para o watcher do
    // Verint (discovery.py, scan não-recursivo) nunca cruzar os dois fluxos.
    // Fase 20 (Call Center Parte III): /opt/audio_upload → /opt/VoipIA/media/sobdemanda.
    @Value("${app.insights.upload-audio-path:/opt/VoipIA/media/sobdemanda}")
    private String insightsUploadAudioPath;

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
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) Integer durationMin,
            @RequestParam(required = false) Integer durationMax,
            @RequestParam(required = false) java.math.BigDecimal notaMin,
            @RequestParam(required = false) java.math.BigDecimal notaMax,
            @RequestParam(required = false) Boolean isFailed,
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) String disconnectedBy,
            @RequestParam(required = false) Boolean hasHold,
            @RequestParam(required = false) Integer wrapupTimeMin,
            @RequestParam(required = false) Integer wrapupTimeMax,
            @RequestParam(required = false) String transferTargetExtension,
            @RequestParam(required = false) String transferTargetAgentName,
            @RequestParam(required = false) String agentLoginId,
            @RequestParam(required = false) String telCliente,
            @RequestParam(required = false) String targetSwitchCallId) {
        boolean isAdmin = isAdmin();
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "callStarttime"));
        InsightsFilter filter = new InsightsFilter(
                id,
                dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                text, phrase, toneCliente, toneAtendente, categoria, criticidade, findingType,
                agentName, direction, skill, durationMin, durationMax, notaMin, notaMax, isFailed,
                extension, disconnectedBy, hasHold, wrapupTimeMin, wrapupTimeMax,
                transferTargetExtension, transferTargetAgentName, agentLoginId, telCliente,
                // Nunca repassa o filtro admin-only pra quem não é ADMIN — defesa em
                // profundidade, o serviço/Specification também ignoram por conta própria.
                isAdmin ? targetSwitchCallId : null);
        return ResponseEntity.ok(queryService.search(filter, pageable, isAdmin));
    }

    @GetMapping("/calls/{id}")
    public ResponseEntity<InsightsDetailResponse> getCall(@PathVariable Long id) {
        // Mesma checagem de permissão/posse do getAudio abaixo — sem isso, o detalhe
        // completo (transcrição, insights, avaliação) de um upload de outro supervisor
        // seria visível a qualquer usuário com insights.calls só variando o id na URL
        // (mesma classe de achado do security-reviewer, 2026-07-20). InsightsAudioFileDto
        // não expõe getSource(), por isso a checagem de posse usa o CallAudioFile cru.
        CallAudioFile rawAudioFile = queryService.findAudioFileById(id);
        if ("upload".equals(rawAudioFile.getSource()) && !canAccessUpload(rawAudioFile)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean isAdmin = isAdmin();
        InsightsDetailResponse detail = queryService.detail(id, isAdmin);
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<InsightsDashboardSummary> dashboard() {
        return ResponseEntity.ok(queryService.dashboard());
    }

    /** Lista paginada de chamadas com tokens/custo estimado de IA — aba "Custos IA". */
    @GetMapping("/costs")
    public ResponseEntity<Page<InsightCostView>> listCosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "callStarttime"));
        return ResponseEntity.ok(costService.findCosts(costFilter(agentName, dateFrom, dateTo), pageable));
    }

    /** Custo de IA agregado por mês — "Dashboard de Custos". */
    @GetMapping("/costs/summary")
    public ResponseEntity<List<InsightMonthlyCostSummary>> costsSummary(
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ResponseEntity.ok(costService.summarizeByMonth(costFilter(agentName, dateFrom, dateTo)));
    }

    private static InsightsCostFilter costFilter(String agentName, LocalDate dateFrom, LocalDate dateTo) {
        // source="verint" fixo — esta aba sempre foi sobre o call center Verint (Fase 3
        // do Quality Management, V40); os custos dos uploads do portal do supervisor têm
        // endpoints próprios em InsightsUploadController.
        return new InsightsCostFilter(
                dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                agentName, "verint", null);
    }

    /** Aba "Processamento" — status/fila de cada arquivo .wav/.xml descoberto em /opt/audio. */
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
        return ResponseEntity.ok(queryService.findProcessing(filter, pageable));
    }

    /**
     * Faz streaming do .wav original em /opt/audio — TRANSCODIFICADO via ffmpeg
     * para PCM WAV 8kHz mono. O arquivo original usa o codec proprietário do
     * Verint (G.729A, ver Fase 0 do plano) — nenhum navegador suporta esse
     * codec nativamente, então servir o arquivo cru resultaria num <audio>
     * mudo/quebrado no frontend.
     *
     * Mesma defesa de path traversal usada em CallRecordController.getAudio:
     * nunca abre o caminho gravado no banco diretamente — extrai só o
     * nome-base do arquivo e resolve dentro do diretório configurado,
     * validando o caminho canônico.
     */
    @GetMapping("/calls/{id}/audio")
    public ResponseEntity<StreamingResponseBody> getAudio(@PathVariable Long id) {
        CallAudioFile audioFile = queryService.findAudioFileById(id);
        if (audioFile.getWavPath() == null || audioFile.getWavPath().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        // Uploads do portal do supervisor (Fase 3, V40) vivem em subpastas por lote
        // (/opt/VoipIA/media/sobdemanda/{batchId}/..., Fase 20) — precisa preservar o
        // subcaminho, não só o nome do arquivo. Verint continua flat em /opt/audio (basename
        // já bastava).
        boolean isUpload = "upload".equals(audioFile.getSource());
        if (isUpload && !canAccessUpload(audioFile)) {
            // 404 (não 403) — mesmo padrão de posse do resto do portal do supervisor
            // (InsightsUploadService.batchDetail): não vaza existência a quem não é
            // dono nem ADMIN, nem a quem só tem insights.calls (achado real do
            // security-reviewer — este endpoint era compartilhado com o fluxo Verint
            // sem checar permissão/posse de upload).
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String baseDir = isUpload ? insightsUploadAudioPath : insightsAudioPath;
        String relativePath = isUpload
                ? pathRelativeToBase(audioFile.getWavPath(), baseDir)
                : new File(audioFile.getWavPath()).getName();
        File resolved = resolveWithinBase(baseDir, relativePath);

        if (resolved == null || !resolved.exists() || !resolved.canRead()) {
            log.warn("Arquivo de áudio não encontrado para insight id={} (arquivo: {})", id, relativePath);
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

    /** Áudio de upload (source='upload') exige a permissão da aba insights.uploads (não
     * basta insights.calls, usada pelo restante deste controller) E posse — ADMIN ou o
     * mesmo username que enviou o lote. Achado real do security-reviewer (2026-07-20):
     * antes desta checagem, qualquer usuário com insights.calls conseguia ouvir áudio de
     * upload de qualquer supervisor só variando o id na URL. */
    /** ADMIN detection (V43) — gate do grupo C (técnico/auditoria) e do filtro
     * targetSwitchCallId, os dois só visíveis/aplicáveis para administradores. Mesmo
     * padrão de canAccessUpload abaixo. */
    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private boolean canAccessUpload(CallAudioFile audioFile) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return false;
        }
        boolean isAdmin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return true;
        }
        boolean hasUploadsPermission = auth.getAuthorities().stream()
                .anyMatch(a -> "PERM_READ_insights.uploads".equals(a.getAuthority())
                        || "PERM_WRITE_insights.uploads".equals(a.getAuthority()));
        return hasUploadsPermission && auth.getName().equals(audioFile.getUploadedBy());
    }

    private String pathRelativeToBase(String storedPath, String baseDir) {
        try {
            File base = new File(baseDir).getCanonicalFile();
            File stored = new File(storedPath).getCanonicalFile();
            String basePath = base.getPath() + File.separator;
            if (stored.getPath().startsWith(basePath)) {
                return stored.getPath().substring(basePath.length());
            }
        } catch (IOException e) {
            log.warn("Erro ao resolver caminho relativo de upload em {}: {}", baseDir, e.getMessage());
        }
        return new File(storedPath).getName();
    }

    private File resolveWithinBase(String baseDir, String fileName) {
        try {
            File base = new File(baseDir).getCanonicalFile();
            File target = new File(base, fileName).getCanonicalFile();
            String basePath = base.getPath() + File.separator;
            if (target.getPath().equals(base.getPath()) || target.getPath().startsWith(basePath)) {
                return target;
            }
        } catch (IOException e) {
            log.warn("Erro ao resolver caminho de áudio em {}: {}", baseDir, e.getMessage());
        }
        return null;
    }
}
