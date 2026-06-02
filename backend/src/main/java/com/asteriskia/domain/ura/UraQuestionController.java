package com.asteriskia.domain.ura;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * UraQuestionController — Endpoints REST para gerenciamento das perguntas da URA.
 *
 * GET  /api/v1/ura/questions        — usado pelo agente Python (JiraCallFlow)
 * POST /api/v1/ura/questions        — adiciona nova pergunta
 * PUT  /api/v1/ura/questions/{id}   — atualiza pergunta
 * DELETE /api/v1/ura/questions/{id} — remove pergunta
 */
@RestController
@RequestMapping("/api/v1/ura/questions")
@RequiredArgsConstructor
@Tag(name = "URA Questions", description = "Gerenciamento das perguntas da URA (Módulo 1)")
public class UraQuestionController {

    private final UraQuestionService service;

    /**
     * Retorna perguntas ativas ordenadas — consumido pelo agente Python no JiraCallFlow.
     * Resposta esperada:
     * [{ "jira_field_key": "...", "question_text": "..." }, ...]
     */
    @GetMapping
    @Operation(summary = "Lista perguntas ativas da URA")
    public ResponseEntity<List<UraQuestionResponse>> getActiveQuestions() {
        List<UraQuestionResponse> questions = service.findActiveQuestions()
                .stream()
                .map(UraQuestionResponse::from)
                .toList();
        return ResponseEntity.ok(questions);
    }

    @GetMapping("/all")
    @Operation(summary = "Lista todas as perguntas (admin)")
    public ResponseEntity<List<UraQuestion>> getAllQuestions() {
        return ResponseEntity.ok(service.findAll());
    }

    @PostMapping
    @Operation(summary = "Cria nova pergunta da URA")
    public ResponseEntity<UraQuestion> createQuestion(@Valid @RequestBody UraQuestion question) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.save(question));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza pergunta da URA")
    public ResponseEntity<UraQuestion> updateQuestion(
            @PathVariable Integer id,
            @Valid @RequestBody UraQuestion question) {
        question.setId(id);
        return ResponseEntity.ok(service.save(question));
    }

    @PatchMapping("/{id}/active")
    @Operation(summary = "Ativa ou desativa pergunta")
    public ResponseEntity<Void> setActive(@PathVariable Integer id, @RequestParam boolean active) {
        service.setActive(id, active);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove pergunta da URA")
    public ResponseEntity<Void> deleteQuestion(@PathVariable Integer id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------------
    // DTO interno — formato esperado pelo agente Python
    // ---------------------------------------------------------------------------
    public record UraQuestionResponse(
            Integer id,
            Integer question_order,
            String question_text,
            String jira_field_key,
            String expected_values
    ) {
        static UraQuestionResponse from(UraQuestion q) {
            return new UraQuestionResponse(
                    q.getId(),
                    q.getQuestionOrder(),
                    q.getQuestionText(),
                    q.getJiraFieldKey(),
                    q.getExpectedValues()
            );
        }
    }
}
