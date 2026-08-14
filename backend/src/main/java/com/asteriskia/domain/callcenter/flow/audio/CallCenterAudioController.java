package com.asteriskia.domain.callcenter.flow.audio;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAudioController — biblioteca de áudios do Flow Builder (Fase 5c). Autorização em
 * {@code SecurityConfig} sob o mesmo resource {@code callcenter.fluxos} que já protege o editor de
 * fluxos — a biblioteca é ferramenta do próprio Flow Builder, não uma tela separada.
 */
@RestController
@RequestMapping("/api/v1/callcenter/audios")
@RequiredArgsConstructor
public class CallCenterAudioController {

    private final CallCenterAudioService audioService;
    private final AudioUploadRateLimiter rateLimiter;

    @GetMapping
    public ResponseEntity<List<CcAudioFileDto>> list() {
        return ResponseEntity.ok(audioService.list());
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<CcAudioFileDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long businessUnitId) {
        String username = currentUsername();
        if (!rateLimiter.allow(username)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Muitos uploads — tente novamente em breve.");
        }
        return ResponseEntity.ok(audioService.upload(file, name, businessUnitId, username));
    }

    /** Pré-escuta no editor — já PCM wav, sem transcodificação (diferente de InsightsController,
     * que transcodifica G.729A). {@code fileName} nunca vem de entrada do usuário. */
    @GetMapping("/{id}/stream")
    public ResponseEntity<FileSystemResource> stream(@PathVariable Long id) {
        return audioService
                .resolveFile(id)
                .map(file -> ResponseEntity.ok().contentType(MediaType.valueOf("audio/wav")).body(new FileSystemResource(file)))
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        audioService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        return auth.getName();
    }
}
