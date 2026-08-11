package com.asteriskia.domain.ura;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * UraQuestionController — Endpoints REST para gerenciamento das perguntas de uma URA.
 *
 * <p>GET /api/v1/uras/{uraId}/questions — usado pelo agente Python (JiraCallFlow) GET
 * /api/v1/uras/{uraId}/questions/all — todas as perguntas (admin) POST
 * /api/v1/uras/{uraId}/questions — adiciona nova pergunta PUT /api/v1/uras/{uraId}/questions/{id} —
 * atualiza pergunta DELETE /api/v1/uras/{uraId}/questions/{id} — remove pergunta
 */
@RestController
@RequestMapping("/api/v1/uras/{uraId}/questions")
@RequiredArgsConstructor
public class UraQuestionController {

    private final UraQuestionService service;

    /**
     * Retorna perguntas ativas ordenadas — consumido pelo agente Python no JiraCallFlow. Resposta
     * esperada: [{ "jira_field_key": "...", "question_text": "..." }, ...]
     */
    @GetMapping
    public ResponseEntity<List<UraQuestionResponse>> getActiveQuestions(
            @PathVariable Integer uraId) {
        List<UraQuestionResponse> questions =
                service.findActiveQuestions(uraId).stream().map(UraQuestionResponse::from).toList();
        return ResponseEntity.ok(questions);
    }

    @GetMapping("/all")
    public ResponseEntity<List<UraQuestion>> getAllQuestions(@PathVariable Integer uraId) {
        return ResponseEntity.ok(service.findAll(uraId));
    }

    @PostMapping
    public ResponseEntity<UraQuestion> createQuestion(
            @PathVariable Integer uraId, @Valid @RequestBody UraQuestion question) {
        question.setUraId(uraId);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.save(question));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UraQuestion> updateQuestion(
            @PathVariable Integer uraId,
            @PathVariable Integer id,
            @Valid @RequestBody UraQuestion question) {
        question.setId(id);
        question.setUraId(uraId);
        return ResponseEntity.ok(service.save(question));
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<Void> setActive(
            @PathVariable Integer uraId, @PathVariable Integer id, @RequestParam boolean active) {
        service.setActive(id, active);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable Integer uraId, @PathVariable Integer id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------------
    // DTO interno — formato esperado pelo agente Python
    // ---------------------------------------------------------------------------
}
