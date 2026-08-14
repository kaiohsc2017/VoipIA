package com.asteriskia.domain.callcenter.kb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CallCenterKbFetchServiceTest — cobre o guard de SSRF (D22, risco central da fonte externa por
 * URL): host privado/loopback/link-local e esquema diferente de http(s) nunca chegam a fazer uma
 * chamada HTTP real.
 */
class CallCenterKbFetchServiceTest {

    private final CallCenterKbFetchService service = new CallCenterKbFetchService(WebClient.builder());

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
}
