package com.asteriskia.domain.call;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * CallRecordController — Endpoints REST para registros de chamada (Módulo 1).
 *
 * POST /api/v1/calls/register — consumido pelo agente Python (JiraCallFlow)
 * GET  /api/v1/calls           — lista paginada de chamadas
 * GET  /api/v1/calls/{id}      — detalhe de uma chamada
 * GET  /api/v1/calls/{id}/audio — streaming do áudio gravado
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallRecordController {

    private final CallRecordService service;
    private final ExcelExportService excelExportService;

    @Value("${app.audio.storage-path:/var/asteriskia/recordings}")
    private String audioStoragePath;

    /**
     * Registra chamada da URA e cria issue no Jira.
     * Payload esperado pelo agente Python:
     * {
     *   "callUuid": "...",
     *   "fields": { "customfield_nome_cliente": "...", "description": "..." }
     * }
     */
    @PostMapping("/register")
        public ResponseEntity<RegisterCallResponse> registerCall(
            @Valid @RequestBody RegisterCallRequest request) {
        CallRecord record = service.registerCall(
                request.callUuid(),
                request.uraId(),
                request.fields(),
                request.audioFilePath(),
                request.transcription(),
                request.callerNumber(),
                request.callDurationSecs(),
                request.subjectTag()
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new RegisterCallResponse(record.getId(), record.getJiraIssueKey()));
    }

    @GetMapping
    public ResponseEntity<Page<CallRecord>> listCalls(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer uraId,
            @RequestParam(required = false) String callerNumber,
            @RequestParam(required = false) String clientName,
            @RequestParam(required = false) String ramal,
            @RequestParam(required = false) String callType,
            @RequestParam(required = false) String jiraIssueKey,
            @RequestParam(required = false) String transcriptionText,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String subjectTag,
            @RequestParam(required = false) String jiraResolution) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "callDate"));

        boolean hasAdvancedFilter = uraId != null || clientName != null || ramal != null || callType != null
                || jiraIssueKey != null || transcriptionText != null || priority != null
                || dateFrom != null || dateTo != null || subjectTag != null || jiraResolution != null;

        Page<CallRecord> result;
        if (hasAdvancedFilter) {
            CallRecordFilter filter = new CallRecordFilter(
                    uraId, callerNumber, clientName, ramal, callType, jiraIssueKey, transcriptionText, priority,
                    dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                    dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                    subjectTag, jiraResolution
            );
            result = service.findByFilters(filter, pageable);
        } else if (callerNumber != null) {
            result = service.findByCallerNumber(callerNumber, pageable);
        } else {
            result = service.findAll(pageable);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
        public ResponseEntity<CallRecord> getCall(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @GetMapping("/export")
        public ResponseEntity<byte[]> exportCalls() throws java.io.IOException {
        java.util.List<CallRecord> records = service.findAll(PageRequest.of(0, 10000)).getContent();
        byte[] excelBytes = excelExportService.exportCallRecordsToExcel(records);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "chamadas.xlsx");
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

        return new ResponseEntity<>(excelBytes, headers, HttpStatus.OK);
    }

    /**
     * Faz streaming do arquivo de áudio gravado pelo Asterisk.
     * O caminho é relativo ao storage configurado (app.audio.storage-path).
     * Também busca diretamente em /var/spool/asterisk/monitor (volume compartilhado).
     */
    @GetMapping("/{id}/audio")
        public ResponseEntity<Resource> getAudio(@PathVariable Long id) {
        CallRecord record = service.findById(id);
        if (record.getAudioFilePath() == null || record.getAudioFilePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        // SEGURANÇA (path traversal): nunca abrir o caminho arbitrário vindo do
        // registro. Usa APENAS o nome-base do arquivo, resolvido dentro de
        // diretórios confiáveis, e valida o caminho canônico contra escape (../).
        String fileName = new File(record.getAudioFilePath()).getName();

        String[] baseDirs = { audioStoragePath, "/var/spool/asterisk/monitor" };
        File audioFile = null;
        for (String baseDir : baseDirs) {
            File candidate = resolveWithinBase(baseDir, fileName);
            if (candidate != null && candidate.exists() && candidate.canRead()) {
                audioFile = candidate;
                break;
            }
        }

        if (audioFile == null) {
            log.warn("Arquivo de áudio não encontrado para chamada id={} (arquivo: {})", id, fileName);
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(audioFile);
        String filename = audioFile.getName();
        MediaType mediaType = filename.endsWith(".mp3") ? MediaType.valueOf("audio/mpeg")
                : filename.endsWith(".ogg") ? MediaType.valueOf("audio/ogg")
                : MediaType.valueOf("audio/wav");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(mediaType)
                .body(resource);
    }

    /**
     * Resolve {@code fileName} dentro de {@code baseDir} e garante, via caminho
     * canônico, que o resultado não escapa do diretório base (defesa contra
     * path traversal com "../"). Retorna null se escapar ou em erro de I/O.
     */
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

    // ---------------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------------

    /** Request do agente Python para registrar a chamada. */
    public record RegisterCallRequest(
            @NotBlank String callUuid,
            Integer uraId,            // qual URA conduziu a chamada — null = URA legada (id=1)
            Map<String, String> fields,
            String audioFilePath,     // caminho do .wav gravado pelo agente Python
            String transcription,     // transcrição completa consolidada
            String callerNumber,      // número do chamador (CALLERID do Asterisk)
            Integer callDurationSecs, // duração total da chamada em segundos
            String subjectTag         // assunto classificado por IA (best-effort, pode vir null)
    ) {}

    /** Resposta com o ID interno e a chave do issue Jira. */
    public record RegisterCallResponse(
            Long id,
            String jiraIssueKey
    ) {}
}
