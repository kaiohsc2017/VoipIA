package com.asteriskia.domain.call;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
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
@Tag(name = "Call Records", description = "Registro de chamadas da URA e integração com Jira (Módulo 1)")
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
    @Operation(summary = "Registra chamada da URA e abre issue no Jira")
    public ResponseEntity<RegisterCallResponse> registerCall(
            @Valid @RequestBody RegisterCallRequest request) {
        CallRecord record = service.registerCall(request.callUuid(), request.fields());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new RegisterCallResponse(record.getId(), record.getJiraIssueKey()));
    }

    @GetMapping
    @Operation(summary = "Lista chamadas (paginado)")
    public ResponseEntity<Page<CallRecord>> listCalls(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String callerNumber) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<CallRecord> result = callerNumber != null
                ? service.findByCallerNumber(callerNumber, pageable)
                : service.findAll(pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalhe de uma chamada")
    public ResponseEntity<CallRecord> getCall(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @GetMapping("/export")
    @Operation(summary = "Exportar chamadas para Excel")
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
     */
    @GetMapping("/{id}/audio")
    @Operation(summary = "Streaming do áudio gravado da chamada URA")
    public ResponseEntity<Resource> getAudio(@PathVariable Long id) {
        CallRecord record = service.findById(id);
        if (record.getAudioFilePath() == null || record.getAudioFilePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        File audioFile = new File(record.getAudioFilePath());
        if (!audioFile.isAbsolute()) {
            audioFile = new File(audioStoragePath, record.getAudioFilePath());
        }

        if (!audioFile.exists() || !audioFile.canRead()) {
            log.warn("Arquivo de áudio não encontrado: {}", audioFile.getAbsolutePath());
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
            Map<String, String> fields
    ) {}

    /** Resposta com o ID interno e a chave do issue Jira. */
    public record RegisterCallResponse(
            Long id,
            String jiraIssueKey
    ) {}
}
