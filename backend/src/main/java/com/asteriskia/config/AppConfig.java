package com.asteriskia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


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
     * RestTemplate para chamadas HTTP síncronas (usado pelo SettingsTestController).
     * Timeout de 8s para evitar bloqueio em testes de conectividade.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(java.time.Duration.ofSeconds(8))
                .setReadTimeout(java.time.Duration.ofSeconds(8))
                .build();
    }
}
