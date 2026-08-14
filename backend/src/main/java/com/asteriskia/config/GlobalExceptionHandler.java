package com.asteriskia.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler — Captura exceções não tratadas e formata as respostas JSON.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Parâmetro inválido: " + ex.getName()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException ex) {
        // Rota inexistente (URL errada/removida) — não é um erro do servidor, é
        // um 404 comum. Sem este handler específico, caía no catch-all de
        // RuntimeException/Exception abaixo e virava 500 "erro fatal" no log,
        // mascarando o que geralmente é só um bug de URL no frontend.
        log.debug("Rota não encontrada: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Recurso não encontrado"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.debug("Recurso não encontrado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        // Gap pré-existente encontrado durante o deploy da Fase 17 (co-browsing): faltar um
        // header obrigatório (ex: Authorization nos endpoints públicos de chat) é entrada
        // inválida do cliente, não um erro do servidor — sem este handler caía no catch-all
        // de Exception abaixo e virava sempre 500 "erro inesperado".
        log.debug("Header obrigatório ausente: {}", ex.getHeaderName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Header obrigatório ausente: " + ex.getHeaderName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex) {
        // Mesma classe de achado já corrigida para MissingRequestHeaderException: faltar um
        // @RequestParam obrigatório (ex: from/to na tela de traço de execução, Fase 5f.2) é
        // entrada inválida do cliente, não um erro do servidor — sem este handler caía no
        // catch-all de Exception abaixo e virava sempre 500 "erro fatal".
        log.debug("Parâmetro obrigatório ausente: {}", ex.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Parâmetro obrigatório ausente: " + ex.getParameterName()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(ResponseStatusException ex) {
        // Achado durante a validação em produção do módulo Financeiro: sem este handler,
        // ResponseStatusException (usada em vários controllers — InsightsUploadController,
        // AgentReportController, CostAlertController — para 404/401/409/400) caía no
        // catch-all de RuntimeException abaixo, virando sempre 500 e mascarando o status
        // real (e a mensagem, quando não sensível) que o próprio código já tinha decidido.
        log.debug("ResponseStatusException: {} — {}", ex.getStatusCode(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() != null ? ex.getReason() : "Erro na requisição"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        // Detalhe completo só no log do servidor — nunca no corpo da resposta,
        // que pode conter fragmentos de SQL, caminhos de arquivo ou nomes de
        // configuração internos (ex: exceções do Hibernate, IllegalArgumentException).
        log.error("Erro interno detectado: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Erro interno do servidor"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception ex) {
        log.error("Erro fatal detectado: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Erro inesperado. Contate o suporte."));
    }
}
