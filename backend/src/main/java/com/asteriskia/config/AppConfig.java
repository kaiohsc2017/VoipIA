package com.asteriskia.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * AppConfig — Configurações gerais da aplicação.
 *
 * Contém:
 *   - CORS: libera origens do frontend React
 *   - WebClient: cliente HTTP reativo para chamadas externas (Jira, Zabbix, Telegram)
 *   - OpenAPI: documentação Swagger com informações do projeto
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

    /**
     * Configuração do OpenAPI (Swagger UI).
     * Acessível em: /swagger-ui.html
     */
    @Bean
    public OpenAPI asteriskIaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AsteriskIA API")
                        .description(
                                "API REST do sistema AsteriskIA — Asterisk + IA em Docker. " +
                                "Módulos: Registro de Chamadas no Jira, Teste de Conectividade e Monitoramento de Infraestrutura."
                        )
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("AsteriskIA Team")
                        )
                )
                .servers(List.of(
                        new Server().url("/").description("Servidor atual")
                ));
    }
}
