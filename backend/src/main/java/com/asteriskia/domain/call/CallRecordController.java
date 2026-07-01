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
                request.callDurationSecs()
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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "callDate"));

        boolean hasAdvancedFilter = uraId != null || clientName != null || ramal != null || callType != null
                || jiraIssueKey != null || transcriptionText != null || priority != null
                || dateFrom != null || dateTo != null;

        Page<CallRecord> result;
        if (hasAdvancedFilter) {
            CallRecordFilter filter = new CallRecordFilter(
                    uraId, callerNumber, clientName, ramal, callType, jiraIssueKey, transcriptionText, priority,
                    dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                    dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null
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

        // Tenta localizar o arquivo em múltiplos caminhos:
        // 1. Caminho absoluto como recebido do ai-agent
        // 2. AUDIO_STORAGE_PATH + nome do arquivo
        // 3. /var/spool/asterisk/monitor + nome do arquivo (volume compartilhado)
        File audioFile = null;
        String storedPath = record.getAudioFilePath();
        String fileName   = new File(storedPath).getName();

        File[] candidates = {
            new File(storedPath),
            new File(audioStoragePath, fileName),
            new File("/var/spool/asterisk/monitor", fileName),
        };

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canRead()) {
                audioFile = candidate;
                break;
            }
        }

        if (audioFile == null) {
            log.warn("Arquivo de áudio não encontrado. Tentativas: {}, {}, {}",
                candidates[0].getAbsolutePath(),
                candidates[1].getAbsolutePath(),
                candidates[2].getAbsolutePath());
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
            Integer callDurationSecs  // duração total da chamada em segundos
    ) {}

    /** Resposta com o ID interno e a chave do issue Jira. */
    public record RegisterCallResponse(
            Long id,
            String jiraIssueKey
    ) {}
}
