package com.asteriskia.domain.callcenter.kb;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CallCenterKbFetchService — busca o conteúdo de uma fonte externa por URL (Fase 25, §25.2).
 *
 * <p><b>SSRF é o risco central</b> (D22, item explícito do plano): a URL é cadastro livre de um
 * usuário com {@code PERM_WRITE_callcenter.kb} — sem o guard abaixo, um cadastro apontando para
 * {@code 172.16.7.11:5432} ou {@code 169.254.169.254} faria o backend fazer a chamada por trás.
 * Mesmo guard (resolve o host, bloqueia IP privado/loopback/link-local/multicast) já usado em
 * {@code SettingsTestController}; duplicado aqui em vez de extraído para uma classe compartilhada
 * — mesmo padrão de não-DRY já aceito no projeto para este guard específico. Resíduo conhecido e
 * já aceito no projeto: não cobre DNS rebinding (o host é resolvido de novo na conexão real).
 */
@Slf4j
@Component
public class CallCenterKbFetchService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_BODY_CHARS = 200_000;

    private final WebClient webClient;

    public CallCenterKbFetchService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public record FetchResult(boolean success, String text, String error) {
        static FetchResult ok(String text) {
            return new FetchResult(true, text, null);
        }

        static FetchResult failed(String error) {
            return new FetchResult(false, null, error);
        }
    }

    public FetchResult fetch(String url) {
        if (!isSafePublicUrl(url)) {
            return FetchResult.failed("URL inválida ou aponta para um host privado/interno.");
        }
        try {
            String body =
                    webClient
                            .get()
                            .uri(URI.create(url))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(REQUEST_TIMEOUT);
            if (body == null || body.isBlank()) {
                return FetchResult.failed("Resposta vazia.");
            }
            var text = stripHtml(body);
            return FetchResult.ok(text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) : text);
        } catch (Exception e) {
            // Nunca e.getMessage() no resultado persistido (last_fetch_error) — mesma defesa em
            // profundidade já aplicada em CallCenterNpsTranscriptionScheduler: exceptions HTTP
            // podem incluir a URI completa (com eventuais credenciais na query string).
            log.warn("Falha ao buscar fonte externa (causa={})", e.getClass().getSimpleName());
            return FetchResult.failed("Falha ao buscar: " + e.getClass().getSimpleName());
        }
    }

    /** Remoção ingênua de tags HTML — suficiente para extrair texto corrido de uma página
     * simples; não é um parser HTML completo (YAGNI para o volume desta base própria). */
    private String stripHtml(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private boolean isSafePublicUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return false;
            }
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()
                        || isIpv6UniqueLocal(addr)) {
                    return false;
                }
            }
            return true;
        } catch (URISyntaxException | java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Achado de revisão de segurança: {@code InetAddress.isSiteLocalAddress()} só reconhece o
     * prefixo IPv6 obsoleto {@code fec0::/10} — não cobre o range moderno de Unique Local Address
     * {@code fc00::/7} (ex.: {@code fd00::/8}), convenção comum para endereçamento IPv6
     * privado/interno. Checagem explícita do primeiro octeto para fechar esse gap do guard de
     * SSRF. */
    private boolean isIpv6UniqueLocal(InetAddress addr) {
        if (!(addr instanceof java.net.Inet6Address)) {
            return false;
        }
        int firstByte = addr.getAddress()[0] & 0xff;
        return (firstByte & 0xfe) == 0xfc;
    }
}
