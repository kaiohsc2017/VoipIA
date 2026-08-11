package com.asteriskia.domain.insights;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ScorecardController — CRUD de fichas de avaliação de qualidade (Fase 1 do Quality
 * Management, V38). Autorização em SecurityConfig: leitura PERM_READ_insights.scorecards,
 * escrita PERM_WRITE_insights.scorecards.
 */
@RestController
@RequestMapping("/api/v1/insights/scorecards")
@RequiredArgsConstructor
public class ScorecardController {

    private final ScorecardService scorecardService;

    public record ItemRequest(
            Integer ordem,
            @NotBlank String pergunta,
            @NotNull(message = "peso é obrigatório") java.math.BigDecimal peso,
            Integer notaMaxima,
            Boolean isCritical
    ) {}

    public record ScorecardRequest(
            @NotBlank String name,
            String description,
            @NotEmpty @Valid List<ItemRequest> items
    ) {}

    @GetMapping
    public ResponseEntity<List<ScorecardDto>> listAll() {
        return ResponseEntity.ok(scorecardService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScorecardDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(scorecardService.getById(id));
    }

    @PostMapping
    public ResponseEntity<ScorecardDto> create(@Valid @RequestBody ScorecardRequest request) {
        return ResponseEntity.ok(scorecardService.create(request.name(), request.description(), toItemInputs(request.items())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScorecardDto> update(@PathVariable Long id, @Valid @RequestBody ScorecardRequest request) {
        return ResponseEntity.ok(scorecardService.update(id, request.name(), request.description(), toItemInputs(request.items())));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ScorecardDto> activate(@PathVariable Long id) {
        return ResponseEntity.ok(scorecardService.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        scorecardService.deactivate(id);
        return ResponseEntity.ok().build();
    }

    private List<ScorecardService.ItemInput> toItemInputs(List<ItemRequest> items) {
        return items.stream()
                .map(i -> new ScorecardService.ItemInput(i.ordem(), i.pergunta(), i.peso(),
                        i.notaMaxima() != null ? i.notaMaxima() : 10, i.isCritical()))
                .toList();
    }
}
