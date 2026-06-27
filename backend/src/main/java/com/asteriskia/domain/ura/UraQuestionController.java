package com.asteriskia.domain.ura;

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
public class UraQuestionController {

    private final UraQuestionService service;

    /**
     * Retorna perguntas ativas ordenadas — consumido pelo agente Python no JiraCallFlow.
     * Resposta esperada:
     * [{ "jira_field_key": "...", "question_text": "..." }, ...]
     */
    @GetMapping
        public ResponseEntity<List<UraQuestionResponse>> getActiveQuestions() {
        List<UraQuestionResponse> questions = service.findActiveQuestions()
                .stream()
                .map(UraQuestionResponse::from)
                .toList();
        return ResponseEntity.ok(questions);
    }

    @GetMapping("/all")
    public ResponseEntity<List<UraQuestion>> getAllQuestions() {
        return ResponseEntity.ok(service.findAll());
    }

    @PostMapping
        public ResponseEntity<UraQuestion> createQuestion(@Valid @RequestBody UraQuestion question) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.save(question));
    }

    @PutMapping("/{id}")
        public ResponseEntity<UraQuestion> updateQuestion(
            @PathVariable Integer id,
            @Valid @RequestBody UraQuestion question) {
        question.setId(id);
        return ResponseEntity.ok(service.save(question));
    }

    @PatchMapping("/{id}/active")
        public ResponseEntity<Void> setActive(@PathVariable Integer id, @RequestParam boolean active) {
        service.setActive(id, active);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
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
