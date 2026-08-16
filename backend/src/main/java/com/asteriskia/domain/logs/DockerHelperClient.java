package com.asteriskia.domain.logs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * DockerHelperClient — comunicação HTTP com o docker-helper (único container com acesso ao
 * docker.sock, F-CRIT-10), extraído de LogsController (fase 4 da refatoração). Este cliente não
 * roda mais 'docker logs'/'docker exec' via ProcessBuilder — tudo passa pela API interna estreita
 * do docker-helper, autenticada via X-Internal-Key.
 */
@Slf4j
@Component
public class DockerHelperClient {

    private final RestTemplate restTemplate;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${app.docker-helper.url}")
    private String dockerHelperUrl;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    public DockerHelperClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Chama o docker-helper (GET /logs/{svc}) — antigo ProcessBuilder("docker","logs",...). Falha
     * de UM serviço (ex: nome inválido) não derruba a consulta dos demais.
     */
    @SuppressWarnings("unchecked")
    public List<String> runDockerLogs(String svc, int lines, String since, String until) {
        try {
            UriComponentsBuilder b =
                    UriComponentsBuilder.fromHttpUrl(dockerHelperUrl + "/logs/" + svc)
                            .queryParam("tail", lines);
            if (since != null) b.queryParam("since", since);
            if (until != null) b.queryParam("until", until);
            Map<String, Object> body = callHelper(b.toUriString());
            return body != null ? (List<String>) body.getOrDefault("lines", List.of()) : List.of();
        } catch (Exception e) {
            log.warn("runDockerLogs({}): {}", svc, e.getMessage());
            return List.of();
        }
    }

    /** Chama o docker-helper (GET /asterisk/log) — antigo docker exec voipia-asterisk tail. */
    @SuppressWarnings("unchecked")
    public List<String> tailAsteriskLog(int lines) {
        String url =
                UriComponentsBuilder.fromHttpUrl(dockerHelperUrl + "/asterisk/log")
                        .queryParam("lines", lines)
                        .toUriString();
        Map<String, Object> body = callHelper(url);
        return body != null ? (List<String>) body.getOrDefault("lines", List.of()) : List.of();
    }

    /** Consome um endpoint de streaming (text/plain, linha por linha) do docker-helper. */
    public Stream<String> streamFromHelper(String path) throws IOException, InterruptedException {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(dockerHelperUrl + path))
                        .header("X-Internal-Key", internalApiKey)
                        .GET()
                        .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofLines()).body();
    }

    private Map<String, Object> callHelper(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalApiKey);
        ResponseEntity<Map> resp =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        return resp.getBody();
    }
}
