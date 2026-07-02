package com.asteriskia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.net.HttpURLConnection;


/**
 * AppConfig — Configurações gerais da aplicação.
 *
 * Contém:
 *   - CORS: libera origens do frontend React
 *   - WebClient: cliente HTTP reativo para chamadas externas (Jira, Zabbix, Telegram)
 *   - RestTemplate: cliente HTTP síncrono (testes de conectividade)
 */
@Configuration
public class AppConfig {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * Configura CORS para permitir chamadas do frontend React.
     * Em produção, restringir apenas às origens necessárias.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(allowedOrigins.split(","))
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }

    /**
     * WebClient.Builder para chamadas HTTP reativas a APIs externas.
     * Injetado pelo TelegramBotService, JiraIntegrationService e ZabbixPollingService.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * RestTemplate para chamadas HTTP síncronas (usado só pelo SettingsTestController).
     * Timeout de 8s para evitar bloqueio em testes de conectividade.
     *
     * Achado de segurança (SSRF, complementa SettingsTestController.isSafePublicUrl):
     * HttpURLConnection segue redirect 3xx por padrão — um host público controlado
     * pelo atacante respondia 302 pra um IP privado e a checagem de host seguro era
     * completamente ignorada nessa segunda conexão. Redirect desabilitado nesse
     * request factory; único consumidor deste bean é o teste de conectividade, então
     * não afeta nenhuma integração real (Jira/Zabbix/Telegram usam WebClient).
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        return builder
                .requestFactory(() -> factory)
                .setConnectTimeout(java.time.Duration.ofSeconds(8))
                .setReadTimeout(java.time.Duration.ofSeconds(8))
                .build();
    }
}
