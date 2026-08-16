package com.asteriskia.domain.security;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * AsteriskLogClient — busca as últimas linhas do log do Asterisk via docker-helper, extraído de
 * SecurityController (fase 11 da refatoração). Antigo {@code docker exec voipia-asterisk tail}
 * — o docker-helper é o único container com acesso ao docker.sock (F-CRIT-10).
 */
@Component
@RequiredArgsConstructor
public class AsteriskLogClient {

    private final RestTemplate restTemplate;

    @Value("${app.docker-helper.url}")
    private String dockerHelperUrl;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    @SuppressWarnings("unchecked")
    public List<String> tail(int lines) {
        String url =
                UriComponentsBuilder.fromHttpUrl(dockerHelperUrl + "/asterisk/log")
                        .queryParam("lines", lines)
                        .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalApiKey);
        ResponseEntity<Map> resp =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = resp.getBody();
        return body != null ? (List<String>) body.getOrDefault("lines", List.of()) : List.of();
    }
}
