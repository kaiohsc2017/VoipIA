package com.asteriskia.domain.callcenter.recording;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterRecordingController — listagem e streaming das gravações de fila do Call Center
 * (Fase 3). RBAC via {@code PERM_READ_callcenter.gravacoes}/{@code PERM_WRITE_callcenter.gravacoes}
 * (SecurityConfig).
 *
 * GET /api/v1/callcenter/recordings           — lista paginada (filtros: fila/período, escopo BU)
 * GET /api/v1/callcenter/recordings/{id}/audio — streaming do WAV gravado
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/callcenter/recordings")
@RequiredArgsConstructor
public class CallCenterRecordingController {

    private final CallCenterRecordingService service;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Page<CcRecording>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        LocalDateTime from = dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null;
        LocalDateTime to = dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null;
        return ResponseEntity.ok(service.findRecordings(queueId, from, to, pageable));
    }

    /**
     * Streaming direto do WAV (MixMonitor(b) já grava em formato tocável nativamente — sem
     * ffmpeg, diferente do Insights que lida com G.729A da Verint). 404 (nunca 403) tanto para id
     * inexistente quanto para gravação fora do escopo de BU do usuário, e também se o arquivo
     * físico não existir/estiver fora do diretório base — nenhum desses casos deve vazar se o id
     * "existe" ou não.
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable Long id, HttpServletRequest request) {
        var recordingOpt = service.findByIdInScope(id);
        if (recordingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var recording = recordingOpt.get();

        var audioFile = service.resolveAudioFile(recording);
        if (audioFile == null || !audioFile.exists() || !audioFile.canRead()) {
            log.warn("Arquivo de gravação não encontrado para id={}", id);
            return ResponseEntity.notFound().build();
        }

        auditService.log(
                request, "callcenter.recording.play", "Gravação id=" + id + " reproduzida", true);

        Resource resource = new FileSystemResource(audioFile);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + audioFile.getName() + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.valueOf("audio/wav"))
                .body(resource);
    }
}
