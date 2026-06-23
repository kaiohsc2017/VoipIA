package com.asteriskia.domain.pedido;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SuporteController — STUB DE DEMONSTRAÇÃO para function calling da IA.
 *
 * ⚠️  Gera um protocolo aleatório SEM persistência em banco.
 *     Consumido por ai-agent/src/services/gemini_service.py (tool abrir_protocolo_suporte).
 *     Substituir pela integração real (Jira/ITSM) antes de usar em produção.
 */
@RestController
@RequestMapping("/api/v1/suporte")
@Tag(name = "Suporte", description = "Endpoints para abertura de chamados (Simulação IA)")
public class SuporteController {

    @PostMapping("/abrir")
    @Operation(summary = "Abre um novo protocolo de suporte")
    public ResponseEntity<Map<String, Object>> abrirProtocolo(@RequestBody Map<String, Object> request) {
        // Simula a criação de um ticket de suporte e gera um protocolo único
        String descricao = (String) request.getOrDefault("descricao", "Sem descrição");
        String prioridade = (String) request.getOrDefault("prioridade", "MEDIA");
        
        String protocolo = "SUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        
        Map<String, Object> response = new HashMap<>();
        response.put("sucesso", true);
        response.put("protocolo", protocolo);
        response.put("descricao", descricao);
        response.put("prioridade", prioridade);
        response.put("mensagem", "Protocolo " + protocolo + " aberto com sucesso. Nossa equipe entrará em contato em até 2 horas úteis.");
        
        return ResponseEntity.ok(response);
    }
}
