package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.settings.EnvFileStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ConsultarApiNodeHandler — nó "consultar_api" (Fase 10, escopo de SSRF), canal {@code both}.
 * Último nó do catálogo do plano-mãe a sair do estado {@code implementado=false}.
 *
 * <p><b>Desenho anti-SSRF (D22 do plano)</b>: diferente de {@code CallCenterKbFetchService} (Fase
 * 25), a URL NUNCA é digitada livremente no editor de fluxo — {@code settingsKey} referencia uma
 * chave do {@code .env} (mesmo mecanismo/allowlist de {@code TelegramLongPollingClient
 * .resolveToken}) cujo VALOR é a URL real, cadastrada por quem tem {@code PERM_WRITE_
 * telecom.settings}/{@code ROLE_ADMIN} — uma barra de confiança bem mais alta que
 * {@code PERM_WRITE_callcenter.flows}, que só desenha o fluxo. Isso fecha a classe de risco mais
 * óbvia (designer do fluxo apontando pra {@code 172.16.7.11:5432} ou
 * {@code 169.254.169.254}) sem precisar validar host em runtime toda vez — mas o guard de host
 * privado/loopback abaixo (mesmo de {@code CallCenterKbFetchService}) é mantido como defesa em
 * profundidade, caso a URL cadastrada aponte sem querer para algo interno.
 *
 * <p>Deliberadamente sem interpolação de variáveis do fluxo na URL/query string — a URL resolvida
 * é usada tal como está no {@code .env}, sempre requisição {@code GET}, sem corpo. Suportar
 * variáveis reabriria a mesma classe de risco que o desenho acima fecha (o valor de uma variável
 * pode ter vindo de entrada do cliente). Fica para uma sub-fase futura, se necessário.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsultarApiNodeHandler implements NodeHandler {

    private static final Pattern SETTINGS_KEY_PATTERN = Pattern.compile("^CALLCENTER_API_[A-Z0-9_]+_URL$");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RESPONSE_CHARS = 5_000;

    private final EnvFileStore envFileStore;
    private final WebClient.Builder webClientBuilder;

    @Override
    public String nodeType() {
        return "consultar_api";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var driver = context.driver();
        var url = resolveUrl(node.data().property("settingsKey"));
        if (url.isEmpty()) {
            return followFailureOrEnd(graph, node, driver);
        }

        String body;
        try {
            body =
                    webClientBuilder
                            .build()
                            .get()
                            .uri(URI.create(url.get()))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(parseTimeout(node.data().property("timeoutSegundos")));
        } catch (Exception e) {
            log.warn("Falha ao consultar API externa via consultar_api (causa={}).", e.getClass().getSimpleName());
            return followFailureOrEnd(graph, node, driver);
        }

        var variavel = node.data().property("variavel");
        if (variavel != null && !variavel.isBlank()) {
            var truncated = body == null ? "" : body.substring(0, Math.min(body.length(), MAX_RESPONSE_CHARS));
            context.setVariable(variavel, truncated);
            driver.setVariable(variavel, truncated);
        }

        var outgoing = graph.outgoingEdges(node.id());
        if (!outgoing.isEmpty()) {
            return Optional.of(outgoing.get(0));
        }
        driver.end();
        return Optional.empty();
    }

    /** Sem sucesso (chave malformada/não configurada, host bloqueado ou falha de rede): segunda
     * aresta (se houver) sinaliza o ramo de erro ao designer do fluxo; sem ela, encerra — nunca
     * trava a execução esperando algo que já falhou. */
    private Optional<FlowGraph.Edge> followFailureOrEnd(
            FlowGraph graph, FlowGraph.Node node, com.asteriskia.domain.callcenter.flow.engine.ChannelDriver driver) {
        var outgoing = graph.outgoingEdges(node.id());
        if (outgoing.size() > 1) {
            return Optional.of(outgoing.get(1));
        }
        driver.end();
        return Optional.empty();
    }

    private Optional<String> resolveUrl(String settingsKey) {
        if (settingsKey == null || settingsKey.isBlank()) {
            return Optional.empty();
        }
        var trimmed = settingsKey.trim();
        if (!SETTINGS_KEY_PATTERN.matcher(trimmed).matches()) {
            log.warn(
                    "Chave de configuração \"{}\" fora do padrão esperado para consultar_api — ignorada por"
                            + " segurança (nunca resolve chave arbitrária do .env).",
                    trimmed);
            return Optional.empty();
        }
        String url;
        try {
            url = envFileStore.readRaw().get(trimmed);
        } catch (IOException e) {
            log.warn("Falha ao ler configuração para consultar_api (causa={}).", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (url == null || url.isBlank() || !isSafePublicUrl(url)) {
            log.warn("Chave \"{}\" sem valor configurado ou apontando para um host inválido/privado.", trimmed);
            return Optional.empty();
        }
        return Optional.of(url);
    }

    private Duration parseTimeout(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return DEFAULT_TIMEOUT;
            }
            var seconds = Long.parseLong(raw.trim());
            var duration = Duration.ofSeconds(seconds);
            return duration.compareTo(MAX_TIMEOUT) > 0 || duration.isNegative() || duration.isZero()
                    ? DEFAULT_TIMEOUT
                    : duration;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT;
        }
    }

    /** Mesmo guard de {@code CallCenterKbFetchService} (Fase 25) — duplicado de propósito, mesmo
     * padrão de não-DRY já aceito no projeto para este guard específico. Resíduo conhecido: não
     * cobre DNS rebinding (o host é resolvido de novo na conexão real). */
    private boolean isSafePublicUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
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

    private boolean isIpv6UniqueLocal(InetAddress addr) {
        if (!(addr instanceof java.net.Inet6Address)) {
            return false;
        }
        int firstByte = addr.getAddress()[0] & 0xff;
        return (firstByte & 0xfe) == 0xfc;
    }
}
