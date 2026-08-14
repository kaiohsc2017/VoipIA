package com.asteriskia.domain.callcenter.chat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * PublicCallCenterChatController — endpoints "públicos" (nome legado da Fase 7b — decisão D8 do
 * plano já esclarece que a aplicação nunca vai à internet aberta, roda dentro da rede
 * corporativa; "widget público" aqui sempre significou "widget interno", embutido em página da
 * intranet). Cada ação exige um token de sessão validado manualmente (não passa pelo
 * {@code JwtAuthFilter} de RBAC — a rota está em {@code permitAll()} em {@code SecurityConfig})
 * contra o {@code sessionId} da URL — nunca aceita o JWT de staff nem de streaming, nem
 * abre/opera sessão de outro cliente.
 *
 * <p>Fase 24: a fila é resolvida do canal ({@code CcChatChannel.defaultQueue}), não mais de uma
 * variável de ambiente única ({@code app.callcenter.chat.public-queue-id}, removida) — o 503
 * "sem fila configurada" continua existindo, só a fonte da configuração mudou.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/callcenter/chat/public")
@RequiredArgsConstructor
public class PublicCallCenterChatController {

    private static final String WEBCHAT_CHANNEL_CODE = "webchat";

    private final CcChatService chatService;
    private final PublicChatRateLimiter rateLimiter;
    private final com.asteriskia.config.JwtService jwtService;

    // Fase 10, achado HIGH H1: sem teto de tamanho, um IP dentro do limite de rate ainda podia
    // enviar sessões/mensagens com payload gigante — abuso de banco e, quando a sessão está em
    // modo bot, de custo de IA (text entra no prompt do nó consultar_base).
    public record StartSessionRequest(
            @NotBlank @Size(max = 120) String customerRef, @Size(max = 120) String customerName) {}

    public record StartSessionResponse(Long sessionId, String token) {}

    public record CustomerMessageRequest(
            @NotBlank @Size(max = 4000) String text, @Size(max = 120) String customerName) {}

    @PostMapping("/sessions")
    public StartSessionResponse startSession(@jakarta.validation.Valid @RequestBody StartSessionRequest request,
                                              HttpServletRequest httpRequest) {
        String ip = resolveIp(httpRequest);
        if (!rateLimiter.allowSessionStart(ip)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas conversas iniciadas — tente novamente mais tarde.");
        }

        CcChatSession session = chatService.startSession(WEBCHAT_CHANNEL_CODE, request.customerRef(), request.customerName());
        String token = jwtService.generateChatCustomerToken(session.getId());
        log.info("Sessão de chat interna iniciada: id={} ip={}", session.getId(), ip);
        return new StartSessionResponse(session.getId(), token);
    }

    @GetMapping("/sessions/{id}/messages")
    public List<CcChatMessage> messages(@PathVariable Long id, @RequestHeader("Authorization") String authorization) {
        requireValidToken(authorization, id);
        return chatService.listMessages(id);
    }

    @PostMapping("/sessions/{id}/messages")
    public CcChatMessage postMessage(@PathVariable Long id,
                                      @jakarta.validation.Valid @RequestBody CustomerMessageRequest request,
                                      @RequestHeader("Authorization") String authorization) {
        requireValidToken(authorization, id);
        if (!rateLimiter.allowMessage(id)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas mensagens — aguarde um instante.");
        }
        return chatService.postMessage(id, "customer", request.customerName(), request.text());
    }

    private void requireValidToken(String authorizationHeader, Long sessionId) {
        String token = (authorizationHeader != null && authorizationHeader.startsWith("Bearer "))
                ? authorizationHeader.substring(7) : null;
        if (token == null || !jwtService.validateChatCustomerToken(token, sessionId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de sessão inválido ou expirado.");
        }
    }

    /** Mesma lógica de {@code RateLimitFilter.resolveIp} — só confia em X-Forwarded-For/
     * X-Real-IP quando a conexão TCP direta vem do próprio Caddy (único reverse proxy da
     * stack); sem essa checagem, qualquer origem poderia forjar o header e burlar o rate
     * limit por IP. Duplicado aqui (não extraído para utilitário compartilhado) para não
     * acoplar este controller público ao filtro de login — mesmo trade-off já aceito em
     * outros pontos do código que replicam essa extração (AsteriskConfigController,
     * SettingsController) sem confiar cegamente no header. */
    private String resolveIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) return realIp.trim();
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        try {
            return InetAddress.getByName("caddy").getHostAddress().equals(remoteAddr);
        } catch (UnknownHostException e) {
            log.warn("Não foi possível resolver o host 'caddy' — headers de IP encaminhado ignorados.");
            return false;
        }
    }
}
