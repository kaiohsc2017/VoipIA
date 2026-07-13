package com.asteriskia.domain.call;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CallSubjectTagController — Vocabulário de assuntos (subject_tag) já classificados
 * por call_type, consumido pelo ai-agent (jira_call_flow.py) antes de classificar uma
 * nova chamada via LLM, para reaproveitar rótulos existentes. Também expõe a escrita
 * do assunto — usada tanto em tempo real quanto pelo backfill em lote de chamadas antigas.
 *
 * GET   /api/v1/internal/calls/subject-tags?callType=Incidente
 * PATCH /api/v1/internal/calls/{id}/subject-tag
 *
 * Protegido pelo InternalKeyFilter (X-Internal-Key) — mesmo mecanismo do ura-routing.
 */
@RestController
@RequestMapping("/api/v1/internal/calls")
@RequiredArgsConstructor
public class CallSubjectTagController {

    private final CallRecordRepository repository;
    private final CallRecordService service;

    @GetMapping("/subject-tags")
    public ResponseEntity<List<String>> subjectTags(@RequestParam String callType) {
        return ResponseEntity.ok(repository.findDistinctSubjectTagsByCallType(callType));
    }

    @PatchMapping("/{id}/subject-tag")
    public ResponseEntity<Void> updateSubjectTag(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.updateSubjectTag(id, body.get("subjectTag"));
        return ResponseEntity.ok().build();
    }
}
