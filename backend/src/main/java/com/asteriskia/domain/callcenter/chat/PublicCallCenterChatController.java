package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.domain.callcenter.cobrowsing.CcCobrowseSession;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseConsentService;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseIngestService;
import com.asteriskia.domain.callcenter.identity.IdentitySource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    // Fase 17b, item 4(e) do plano: teto de corpo por lote de eventos rrweb — rejeitado ANTES de
    // desserializar o JSON (o parâmetro chega como byte[] cru, não como DTO tipado).
    private static final int MAX_COBROWSE_EVENTS_BODY_BYTES = 512 * 1024;

    private final CcChatService chatService;
    private final PublicChatRateLimiter rateLimiter;
    private final com.asteriskia.config.JwtService jwtService;
    private final CobrowseConsentService cobrowseConsentService;
    private final CobrowseIngestService cobrowseIngestService;
    private final ObjectMapper objectMapper;
    private final ChatAttachmentService attachmentService;

    // Fase 10, achado HIGH H1: sem teto de tamanho, um IP dentro do limite de rate ainda podia
    // enviar sessões/mensagens com payload gigante — abuso de banco e, quando a sessão está em
    // modo bot, de custo de IA (text entra no prompt do nó consultar_base).
    // Fase 14 — networkLogin é opcional e autorrelatado pelo próprio contato (widget interno,
    // D8: rede corporativa, não internet aberta) — nunca falha o início de sessão se inválido.
    // O resultado da resolução NUNCA volta na resposta HTTP (nem como booleano): esse endpoint é
    // público e sem autenticação, e devolver "existe/não existe" ao próprio chamador anônimo é um
    // oráculo de enumeração de login válido do AD corporativo. A identidade fica só persistida
    // (resolved_ad_sam da sessão) para consumo pelo agente/screen pop, nunca refletida de volta.
    public record StartSessionRequest(
            @NotBlank @Size(max = 120) String customerRef,
            @Size(max = 120) String customerName,
            @Size(max = 128) String networkLogin) {}

    public record StartSessionResponse(Long sessionId, String token) {}

    public record CustomerMessageRequest(
            @NotBlank @Size(max = 4000) String text, @Size(max = 120) String customerName) {}

    // Fase 17a — hash SHA-256 (hex) do texto de consentimento exibido no widget; nunca o texto
    // em si trafega aqui.
    public record CobrowseConsentRequest(boolean granted, @NotBlank @Size(max = 64) String textHash) {}

    // Fase 17b — lote de eventos rrweb; "events" é uma lista de objetos JSON arbitrários (o
    // formato interno do rrweb não é modelado aqui — é persistido como está, sem mascaramento).
    // "seq" não é validado hoje (não há reordenação/deduplicação nesta fatia) — só documenta a
    // intenção do widget de numerar seus próprios lotes.
    public record CobrowseEventsRequest(long seq, List<Map<String, Object>> events) {}

    @PostMapping("/sessions")
    public StartSessionResponse startSession(@jakarta.validation.Valid @RequestBody StartSessionRequest request,
                                              HttpServletRequest httpRequest) {
        String ip = resolveIp(httpRequest);
        if (!rateLimiter.allowSessionStart(ip)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas conversas iniciadas — tente novamente mais tarde.");
        }

        CcChatSession session = chatService.startSession(WEBCHAT_CHANNEL_CODE, request.customerRef(), request.customerName());
        String token = jwtService.generateChatCustomerToken(session.getId());
        if (request.networkLogin() != null && !request.networkLogin().isBlank()
                && rateLimiter.allowIdentification(ip)) {
            chatService.resolveIdentity(session.getId(), request.networkLogin(), IdentitySource.URA_INPUT);
        }
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

    /**
     * Fase 17a — aceite/recusa/revogação de co-browsing gravado do chat. Mesma validação manual
     * de token do resto deste controller: nunca aceita JWT de staff, só o token {@code
     * chat_customer} desta sessão específica. 404 (não 403) se não houver
     * {@code CcCobrowseSession} pra este chat (agente sem o toggle ligado, por ex.) — nunca
     * revela mais do que isso a quem tenta um id arbitrário.
     */
    @PostMapping("/sessions/{id}/cobrowse-consent")
    public CcCobrowseSession cobrowseConsent(@PathVariable Long id,
                                              @jakarta.validation.Valid @RequestBody CobrowseConsentRequest request,
                                              @RequestHeader("Authorization") String authorization) {
        requireValidToken(authorization, id);
        if (!rateLimiter.allowCobrowseConsent(id)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições — aguarde um instante.");
        }
        return cobrowseConsentService.registerConsent(id, request.granted(), request.textHash());
    }

    /**
     * Fase 17b — ingestão do lote de eventos rrweb. Corpo recebido cru ({@code byte[]}) para
     * poder rejeitar (413) ANTES de desserializar — desserializar primeiro gastaria a mesma
     * memória que queremos limitar. Guardas de negócio (consentimento/sessão encerrada/toggle do
     * agente/teto acumulado) ficam em {@link CobrowseIngestService}; aqui só corpo grande demais
     * (e) e rate limit (d).
     */
    @PostMapping("/sessions/{id}/cobrowse-events")
    public ResponseEntity<Void> cobrowseEvents(@PathVariable Long id,
                                                @RequestBody byte[] rawBody,
                                                @RequestHeader("Authorization") String authorization) {
        requireValidToken(authorization, id);
        if (rawBody.length > MAX_COBROWSE_EVENTS_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Lote de eventos de co-browsing excede o tamanho máximo permitido.");
        }
        if (!rateLimiter.allowCobrowseEvents(id)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições — aguarde um instante.");
        }
        CobrowseEventsRequest request;
        try {
            request = objectMapper.readValue(rawBody, CobrowseEventsRequest.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo de requisição inválido.");
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo de requisição inválido.");
        }
        cobrowseIngestService.ingest(id, request.events());
        return ResponseEntity.noContent().build();
    }

    /** Fase 7d — anexo enviado pelo cliente do widget (D6: bidirecional). Mesmo token de sessão
     * já validado no resto deste controller; rate limit dedicado para não deixar o upload
     * contornar o limite de mensagens. */
    @PostMapping("/sessions/{id}/attachments")
    public ResponseEntity<CcChatAttachment> uploadAttachment(
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestHeader("Authorization") String authorization) {
        requireValidToken(authorization, id);
        if (!rateLimiter.allowAttachment(id)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitos anexos enviados — aguarde um instante.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(attachmentService.upload(id, "customer", file));
    }

    @GetMapping("/sessions/{id}/attachments")
    public List<CcChatAttachment> attachments(@PathVariable Long id, @RequestHeader("Authorization") String authorization) {
        requireValidToken(authorization, id);
        return attachmentService.list(id);
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadAttachment(
            @PathVariable Long attachmentId, @RequestHeader("Authorization") String authorization) {
        var attachment = attachmentService.findByIdOrThrow(attachmentId);
        requireValidToken(authorization, attachment.getSessionId());
        java.io.File file = attachmentService.resolveFile(attachment);
        if (!file.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo do anexo não encontrado em disco.");
        }
        org.springframework.http.MediaType mediaType;
        try {
            mediaType = attachment.getContentType() != null
                    ? org.springframework.http.MediaType.parseMediaType(attachment.getContentType())
                    : org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        } catch (org.springframework.http.InvalidMediaTypeException e) {
            mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        }
        // Nome original é controlado pelo cliente do widget — nunca interpolado cru no header;
        // ContentDisposition já aplica a codificação RFC 6266/5987 correta.
        String disposition = org.springframework.http.ContentDisposition.attachment()
                .filename(attachment.getOriginalFileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build().toString();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(new org.springframework.core.io.FileSystemResource(file));
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
