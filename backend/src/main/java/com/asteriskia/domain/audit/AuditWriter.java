package com.asteriskia.domain.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * AuditWriter — grava o AuditLog em background.
 *
 * Separado do AuditService por dois motivos:
 * 1. Auto-invocação: se o método @Async estivesse na mesma classe que o
 *    ponto de entrada público, uma chamada interna (this.write(...)) não
 *    passaria pelo proxy do Spring e o @Async seria silenciosamente
 *    ignorado (rodaria síncrono).
 * 2. Nunca recebe o HttpServletRequest cru — só valores primitivos já
 *    extraídos pelo AuditService na thread da requisição. Extrair
 *    request.getHeader(...) dentro do método assíncrono é o que causava
 *    "IllegalStateException: request object has been recycled": a resposta
 *    já tinha sido enviada e o Tomcat reciclou a RequestFacade antes da
 *    task assíncrona rodar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWriter {

    private final AuditLogRepository repo;

    @Async
    public void write(String username, String ip, String userAgent, String action, String details, boolean success) {
        try {
            AuditLog entry = AuditLog.builder()
                    .username(username)
                    .ipAddress(ip)
                    .userAgent(userAgent)
                    .action(action)
                    .details(details)
                    .success(success)
                    .build();
            repo.save(entry);
        } catch (Exception e) {
            // Auditoria nunca deve derrubar a requisição principal
            log.error("Erro ao gravar audit log [action={}]: {}", action, e.getMessage());
        }
    }
}
