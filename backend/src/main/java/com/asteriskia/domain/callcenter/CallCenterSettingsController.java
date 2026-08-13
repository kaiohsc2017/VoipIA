package com.asteriskia.domain.callcenter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterSettingsController — ranges de ramal de agente/fila/fluxo e interruptor global de
 * pesquisa de satisfação (Fase 19 do plano Call Center Parte III). RBAC {@code callcenter.config}
 * — mesmo resource já usado pela tela "Configurações do Call Center" (motivos de pausa e
 * tabulações, Fase 12.6).
 */
@RestController
@RequestMapping("/api/v1/callcenter/settings")
@RequiredArgsConstructor
public class CallCenterSettingsController {

    private final CcSettingsService settingsService;

    public record RangeRequest(@NotNull Integer start, @NotNull Integer end) {}

    public record RangeView(String type, String label, int start, int end) {}

    public record UpdateRangeResult(RangeView range, int extensionsOutsideRange) {}

    public record SettingsView(RangeView agentRange, RangeView queueRange, RangeView flowRange, boolean npsEnabledGlobally) {}

    @GetMapping
    public ResponseEntity<SettingsView> getAll() {
        return ResponseEntity.ok(
                new SettingsView(
                        toView(CcSettingsService.RangeType.AGENT),
                        toView(CcSettingsService.RangeType.QUEUE),
                        toView(CcSettingsService.RangeType.FLOW),
                        settingsService.isNpsEnabledGlobally()));
    }

    @PutMapping("/ranges/{type}")
    public ResponseEntity<UpdateRangeResult> updateRange(
            @PathVariable String type, @Valid @RequestBody RangeRequest request) {
        var rangeType = parseRangeType(type);
        int outside = settingsService.updateRange(rangeType, request.start(), request.end());
        return ResponseEntity.ok(new UpdateRangeResult(toView(rangeType), outside));
    }

    @PutMapping("/nps-enabled")
    public ResponseEntity<Void> setNpsEnabled(@RequestBody NpsToggleRequest request) {
        settingsService.setNpsEnabledGlobally(request.enabled());
        return ResponseEntity.noContent().build();
    }

    public record NpsToggleRequest(boolean enabled) {}

    private RangeView toView(CcSettingsService.RangeType type) {
        var range = settingsService.getRange(type);
        return new RangeView(type.name(), type.label, range.start(), range.end());
    }

    private CcSettingsService.RangeType parseRangeType(String raw) {
        try {
            return CcSettingsService.RangeType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Tipo de range inválido: " + raw + " (esperado agent, queue ou flow)");
        }
    }
}
