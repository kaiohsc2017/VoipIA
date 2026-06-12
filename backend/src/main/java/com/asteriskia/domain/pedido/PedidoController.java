package com.asteriskia.domain.pedido;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * PedidoController — Endpoint simulado para integrações de IA (ex: Gemini).
 * Permite a consulta de status de pedidos pelo identificador (CPF ou número).
 */
@RestController
@RequestMapping("/api/v1/pedidos")
@Tag(name = "Pedidos", description = "Endpoints para consulta de status de pedidos (Simulação IA)")
public class PedidoController {

    @GetMapping("/{identificador}")
    @Operation(summary = "Consulta o status de um pedido pelo identificador")
    public ResponseEntity<Map<String, Object>> consultarPedido(@PathVariable String identificador) {
        // Simula um banco de dados de pedidos (Mock para demonstração do AI Agent)
        String cleanIdent = identificador.replaceAll("[^0-9a-zA-Z]", "");
        
        Map<String, Object> response = new HashMap<>();
        if (cleanIdent.equals("12345678909")) {
            response.put("protocolo", "PED-9901");
            response.put("produto", "Roteador Wi-Fi 6 Plus");
            response.put("status", "EM_TRANSITO");
            response.put("previsao", "Amanhã, 14:00");
            response.put("transportadora", "Loggi");
            return ResponseEntity.ok(response);
        } else if (cleanIdent.equals("98765432100")) {
            response.put("protocolo", "PED-9902");
            response.put("produto", "Modem Fibra Óptica");
            response.put("status", "ENTREGUE");
            response.put("previsao", "Entregue ontem");
            response.put("transportadora", "Correios");
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
