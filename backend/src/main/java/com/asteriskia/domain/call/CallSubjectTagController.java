package com.asteriskia.domain.call;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CallSubjectTagController — Vocabulário de assuntos (subject_tag) já classificados
 * por call_type, consumido pelo ai-agent (jira_call_flow.py) antes de classificar uma
 * nova chamada via LLM, para reaproveitar rótulos existentes.
 *
 * GET /api/v1/internal/calls/subject-tags?callType=Incidente
 *
 * Protegido pelo InternalKeyFilter (X-Internal-Key) — mesmo mecanismo do ura-routing.
 */
@RestController
@RequestMapping("/api/v1/internal/calls")
@RequiredArgsConstructor
public class CallSubjectTagController {

    private final CallRecordRepository repository;

    @GetMapping("/subject-tags")
    public ResponseEntity<List<String>> subjectTags(@RequestParam String callType) {
        return ResponseEntity.ok(repository.findDistinctSubjectTagsByCallType(callType));
    }
}
