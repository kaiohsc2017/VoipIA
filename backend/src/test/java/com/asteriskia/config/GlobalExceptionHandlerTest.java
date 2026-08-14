package com.asteriskia.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;

/**
 * GlobalExceptionHandlerTest — cobre o handler de {@link ResponseStatusException} adicionado
 * durante a validação em produção do módulo Financeiro: sem ele, toda ResponseStatusException
 * lançada por qualquer controller (404/401/409/400 — usados por InsightsUploadController,
 * AgentReportController, CostAlertController) caía no catch-all de RuntimeException e virava
 * sempre 500, mascarando o status real.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleResponseStatusException_preservaOStatusEARazaoOriginais() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope inválido: x");

        ResponseEntity<?> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("error", "scope inválido: x"));
    }

    @Test
    void handleResponseStatusException_semRazao_usaMensagemGenerica() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

        ResponseEntity<?> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("error", "Erro na requisição"));
    }

    /** Achado real na validação em produção da Fase 5f.2 (tela de traço de execução): faltar um
     * @RequestParam obrigatório (ex: from/to) caía no catch-all de Exception e virava 500 "erro
     * fatal" em vez de 400 — mesma classe de bug já corrigida para MissingRequestHeaderException. */
    @Test
    void handleMissingServletRequestParameter_retorna400ComNomeDoParametro() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("from", "String");

        ResponseEntity<?> response = handler.handleMissingServletRequestParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("error", "Parâmetro obrigatório ausente: from"));
    }
}
