package com.asteriskia.domain.pedido;

import com.asteriskia.integration.jira.JiraIntegrationService;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * SuporteController — Abertura de chamados de suporte via function calling da IA.
 *
 * <p>Consumido por ai-agent/src/services/gemini_service.py (tool abrir_protocolo_suporte). Cria uma
 * issue real no Jira via JiraIntegrationService. Se o Jira não estiver configurado, retorna erro
 * claro para a IA informar o cliente.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/suporte")
@RequiredArgsConstructor
public class SuporteController {

    private final JiraIntegrationService jiraService;

    @PostMapping("/abrir")
    public ResponseEntity<Map<String, Object>> abrirProtocolo(
            @Valid @RequestBody AbrirProtocoloRequest request) {
        String descricao = request.descricao();
        String prioridade = request.prioridade() != null ? request.prioridade() : "MEDIA";

        Map<String, Object> response = new HashMap<>();

        // Monta os campos da issue e delega ao JiraIntegrationService
        Map<String, String> fields = new HashMap<>();
        fields.put("description", descricao);
        fields.put("priority", mapPrioridade(prioridade));

        String issueKey = jiraService.createIssue(fields);

        if (issueKey != null) {
            response.put("sucesso", true);
            response.put("protocolo", issueKey);
            response.put("descricao", descricao);
            response.put("prioridade", prioridade);
            response.put(
                    "mensagem",
                    "Protocolo "
                            + issueKey
                            + " aberto com sucesso. "
                            + "Nossa equipe entrará em contato em breve.");
            log.info("Protocolo de suporte aberto no Jira: {}", issueKey);
            return ResponseEntity.ok(response);
        }

        // Jira não configurado ou falha na criação
        response.put("sucesso", false);
        response.put(
                "mensagem",
                "Não foi possível abrir o chamado no momento. "
                        + "Verifique a configuração do Jira em Settings.");
        log.warn("Falha ao abrir protocolo de suporte — Jira indisponível ou não configurado");
        return ResponseEntity.ok(response);
    }

    /** Converte a prioridade da IA (BAIXA/MEDIA/ALTA/CRITICA) para o texto esperado pelo Jira. */
    private String mapPrioridade(String prioridade) {
        return switch (prioridade.toUpperCase().trim()) {
            case "ALTA", "CRITICA" -> "Alta";
            case "BAIXA" -> "Baixa";
            default -> "Média";
        };
    }
}
