package com.asteriskia.domain.alert;

import jakarta.validation.Valid;
import java.io.File;
import java.util.List;
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

/** AlertController — Endpoints REST para alertas Zabbix e contatos de plantão (Módulo 3). */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService service;

    @Value("${app.audio.storage-path:/var/asteriskia/recordings}")
    private String audioStoragePath;

    // -----------------------------------------------------------------------
    // Alert Calls
    // -----------------------------------------------------------------------

    /** Consumido pelo agente Python: busca dados do incidente para leitura via TTS. */
    @GetMapping("/alert-calls/by-uuid/{uuid}")
    public ResponseEntity<AlertCall> getByUuid(@PathVariable String uuid) {
        return service.findByUuid(uuid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Consumido pelo agente Python: atualiza status após o fluxo de voz. */
    @PatchMapping("/alert-calls/by-uuid/{uuid}")
    public ResponseEntity<Void> updateStatus(
            @PathVariable String uuid, @Valid @RequestBody UpdateStatusRequest request) {
        service.updateCallStatus(uuid, request.callStatus());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/alert-calls")
    public ResponseEntity<Page<AlertCall>> listAlerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.findAll(PageRequest.of(page, size)));
    }

    @GetMapping("/alert-calls/{id}")
    public ResponseEntity<AlertCall> getAlert(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Faz streaming do arquivo de audio gravado pelo Asterisk para alertas. */
    @GetMapping("/alert-calls/{id}/audio")
    public ResponseEntity<Resource> getAudio(@PathVariable Long id) {
        var optRecord = service.findById(id);
        if (optRecord.isEmpty()) {
            return ResponseEntity.<Resource>notFound().build();
        }
        AlertCall record = optRecord.get();
        if (record.getAudioFilePath() == null || record.getAudioFilePath().isBlank()) {
            return ResponseEntity.<Resource>notFound().build();
        }
        File audioFile = new File(record.getAudioFilePath());
        if (!audioFile.isAbsolute()) {
            audioFile = new File(audioStoragePath, record.getAudioFilePath());
        }
        if (!audioFile.exists() || !audioFile.canRead()) {
            log.warn("Arquivo de audio do alerta nao encontrado: {}", audioFile.getAbsolutePath());
            return ResponseEntity.<Resource>notFound().build();
        }
        Resource resource = new FileSystemResource(audioFile);
        String filename = audioFile.getName();
        MediaType mediaType =
                filename.endsWith(".mp3")
                        ? MediaType.valueOf("audio/mpeg")
                        : filename.endsWith(".ogg")
                                ? MediaType.valueOf("audio/ogg")
                                : MediaType.valueOf("audio/wav");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(mediaType)
                .body(resource);
    }

    // -----------------------------------------------------------------------
    // Alert Contacts (contatos de plantão)
    // -----------------------------------------------------------------------

    @GetMapping("/alert-contacts")
    public ResponseEntity<List<AlertContact>> listContacts(
            @RequestParam(required = false) Long operationId) {
        return ResponseEntity.ok(service.findActiveContacts(operationId));
    }

    @PostMapping("/alert-contacts")
    public ResponseEntity<AlertContact> createContact(@Valid @RequestBody AlertContact contact) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveContact(contact));
    }

    @PutMapping("/alert-contacts/{id}")
    public ResponseEntity<AlertContact> updateContact(
            @PathVariable Integer id, @Valid @RequestBody AlertContact contact) {
        contact.setId(id);
        return ResponseEntity.ok(service.saveContact(contact));
    }

    @DeleteMapping("/alert-contacts/{id}")
    public ResponseEntity<Void> deleteContact(@PathVariable Integer id) {
        service.deleteContact(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

}
