package com.asteriskia.domain.callcenter.chat;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterChatChannelController — CRUD de canais de chat (Fase 24). Sob o mesmo prefixo
 * {@code /api/v1/callcenter/chat/**} já protegido em {@code SecurityConfig} por
 * {@code callcenter.chat} — nenhum matcher novo necessário.
 */
@RestController
@RequestMapping("/api/v1/callcenter/chat/channels")
@RequiredArgsConstructor
public class CallCenterChatChannelController {

    private final CallCenterChatChannelService service;

    @GetMapping
    public ResponseEntity<List<ChatChannelView>> getAll() {
        return ResponseEntity.ok(service.findAll().stream().map(ChatChannelView::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChatChannelView> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ChatChannelView.from(service.findById(id)));
    }

    @PostMapping
    public ResponseEntity<ChatChannelView> create(@Valid @RequestBody ChatChannelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatChannelView.from(service.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ChatChannelView> update(@PathVariable Long id, @Valid @RequestBody ChatChannelRequest request) {
        return ResponseEntity.ok(ChatChannelView.from(service.update(id, request)));
    }
}
