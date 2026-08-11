package com.asteriskia.domain.ura;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * UraRoutingController — Correlação UUID da chamada → URA que a originou.
 *
 * POST /api/v1/internal/ura-routing            — chamado pelo dialplan (CURL) do Asterisk
 * GET  /api/v1/internal/ura-routing/by-uuid/{uuid} — consumido pelo ai-agent
 *
 * Protegido pelo InternalKeyFilter (X-Internal-Key) — mesmo mecanismo já usado
 * entre ai-agent e backend, não fica exposto sem autenticação.
 */
@RestController
@RequestMapping("/api/v1/internal/ura-routing")
@RequiredArgsConstructor
public class UraRoutingController {

    private final UraRoutingService service;

    @PostMapping
    public ResponseEntity<Void> register(@RequestParam String uuid, @RequestParam String extension) {
        service.register(uuid, extension);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/by-uuid/{uuid}")
    public ResponseEntity<Integer> resolveByUuid(@PathVariable String uuid) {
        Integer uraId = service.resolve(uuid);
        return uraId != null ? ResponseEntity.ok(uraId) : ResponseEntity.notFound().build();
    }
}
