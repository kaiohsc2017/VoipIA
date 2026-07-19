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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
            @RequestParam(required = false) Integer durationMax) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "callStarttime"));
        InsightsFilter filter = new InsightsFilter(
                id,
                dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                text, phrase, toneCliente, toneAtendente, categoria, criticidade, findingType,
                agentName, direction, skill, durationMin, durationMax);
        return ResponseEntity.ok(queryService.search(filter, pageable));
    }

    @GetMapping("/calls/{id}")
    public ResponseEntity<InsightsDetailResponse> getCall(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.detail(id));
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
        return new InsightsCostFilter(
                dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                agentName);
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

        String fileName = new File(audioFile.getWavPath()).getName();
        File resolved = resolveWithinBase(insightsAudioPath, fileName);

        if (resolved == null || !resolved.exists() || !resolved.canRead()) {
            log.warn("Arquivo de áudio não encontrado para insight id={} (arquivo: {})", id, fileName);
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
