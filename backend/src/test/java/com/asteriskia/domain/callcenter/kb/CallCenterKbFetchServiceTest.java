package com.asteriskia.domain.callcenter.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CallCenterKbFetchServiceTest — cobre o guard de SSRF (D22, risco central da fonte externa por
 * URL): host privado/loopback/link-local e esquema diferente de http(s) nunca chegam a fazer uma
 * chamada HTTP real.
 */
class CallCenterKbFetchServiceTest {

    private final CallCenterKbFetchService service = new CallCenterKbFetchService(WebClient.builder());
    private HttpServer redirectServer;

    @AfterEach
    void tearDown() {
        if (redirectServer != null) {
            redirectServer.stop(0);
        }
    }

    @Test
    void fetch_hostLoopback_bloqueiaSemChamarHttp() {
        var result = service.fetch("http://127.0.0.1:8080/secret");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("privado/interno");
    }

    @Test
    void fetch_hostPrivadoRfc1918_bloqueiaSemChamarHttp() {
        var result = service.fetch("http://172.16.7.11:5432/");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("privado/interno");
    }

    @Test
    void fetch_hostLinkLocalMetadata_bloqueiaSemChamarHttp() {
        var result = service.fetch("http://169.254.169.254/latest/meta-data");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("privado/interno");
    }

    @Test
    void fetch_esquemaNaoHttp_bloqueado() {
        var result = service.fetch("file:///etc/passwd");

        assertThat(result.success()).isFalse();
    }

    @Test
    void fetch_urlMalformada_bloqueadaSemLancarExcecao() {
        var result = service.fetch("not a url");

        assertThat(result.success()).isFalse();
    }

    /** Fase 10, achado MEDIUM M7: o guard de SSRF do serviço só valida a URL de <b>entrada</b> —
     * a proteção contra SSRF-por-redirect depende inteiramente do cliente HTTP subjacente não
     * seguir automaticamente um 3xx para um destino não revalidado. O host de entrada aqui é
     * obrigatoriamente loopback (o único jeito de montar um servidor de teste local), então o
     * guard do próprio serviço já bloquearia por outro motivo — este teste isola e prova a
     * premissa real: um {@code WebClient} construído do mesmo jeito que o do serviço
     * ({@code WebClient.builder().build()}, sem configuração de redirect) nunca segue o 302
     * automaticamente (Reactor Netty não segue redirect por padrão). Se essa premissa algum dia
     * deixar de valer (troca de builder, configuração global de {@code ExchangeStrategies}), este
     * teste passa a falhar e sinaliza a regressão antes de qualquer achado de SSRF real. */
    @Test
    void webClientPadrao_naoSegueRedirect3xxAutomaticamente() throws Exception {
        redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectServer.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/secreto");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirectServer.createContext("/secreto", exchange -> {
            byte[] body = "CONTEUDO_QUE_NAO_DEVERIA_SER_ALCANCADO".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        redirectServer.start();
        int port = redirectServer.getAddress().getPort();

        var plainWebClient = WebClient.builder().build();
        var responseStatus =
                plainWebClient
                        .get()
                        .uri("http://127.0.0.1:" + port + "/redirect")
                        .exchangeToMono(response -> reactor.core.publisher.Mono.just(response.statusCode().value()))
                        .block(Duration.ofSeconds(5));

        assertThat(responseStatus).isEqualTo(302);
    }
}
