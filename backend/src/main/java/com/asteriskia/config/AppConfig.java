package com.asteriskia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;


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

    @Value("${app.callcenter.chat.allowed-origins:}")
    private String chatAllowedOrigins;

    /**
     * CORS consumido por {@code SecurityConfig} via {@code http.cors(...)} (não
     * {@code WebMvcConfigurer.addCorsMappings}, e não um {@code CorsFilter} avulso).
     *
     * <p>Achado de bug #1: com duas entradas em {@code CorsRegistry} cujos padrões se
     * sobrepõem ({@code /api/**} e {@code /api/v1/callcenter/chat/public/**}), o
     * {@code UrlBasedCorsConfigurationSource} do Spring MVC COMBINA as duas configurações pra
     * qualquer request sob a rota pública — e a combinação de {@code allowCredentials=true}
     * (da regra geral) com {@code allowedOrigins=*} (da regra do widget) é uma combinação
     * inválida que o {@code DefaultCorsProcessor} rejeita com 403 "Invalid CORS request".
     * Resolvido decidindo a configuração inteira por request (branch manual por path), nunca
     * combinando duas configurações parciais.
     *
     * <p>Achado de bug #2: um {@code CorsFilter} criado como {@code @Bean} avulso (sem
     * {@code @Order}) é registrado pelo Spring Boot com precedência baixa — a cadeia do
     * Spring Security roda primeiro e barrava o preflight OPTIONS de qualquer rota
     * autenticada com 403, antes do CorsFilter ter chance de responder. Corrigido consumindo
     * este bean diretamente em {@code SecurityConfig.http.cors(...)}, que integra o CORS
     * DENTRO da cadeia de segurança — o Spring Security já sabe reconhecer e liberar
     * preflight antes da checagem de autorização quando configurado dessa forma.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration restricted = new CorsConfiguration();
        restricted.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
        restricted.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        restricted.setAllowedHeaders(List.of("*"));
        restricted.setAllowCredentials(true);
        restricted.setMaxAge(3600L);

        // Widget de chat "público" (Fase 7b) — nome legado; D8 (2026-08-08) já esclareceu que a
        // aplicação nunca vai à internet aberta, roda dentro da rede corporativa. Fase 24:
        // origem "*" (pensada originalmente pra widget embutido em site externo) trocada por
        // uma lista configurável de origens corporativas reais — vazia nesta VPS de dev
        // (nenhuma origem cross-origin liberada até ser configurada de verdade). Continua sem
        // allowCredentials=true: o token de sessão viaja em header/body, nunca em cookie, então
        // não há CSRF a mitigar nesta rota mesmo com a lista vazia/restrita.
        CorsConfiguration publicChat = new CorsConfiguration();
        var chatOrigins = chatAllowedOrigins == null || chatAllowedOrigins.isBlank()
                ? List.<String>of()
                : java.util.Arrays.stream(chatAllowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
        publicChat.setAllowedOriginPatterns(chatOrigins);
        publicChat.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        publicChat.setAllowedHeaders(List.of("*"));
        publicChat.setAllowCredentials(false);
        publicChat.setMaxAge(3600L);

        return request -> request.getRequestURI().startsWith("/api/v1/callcenter/chat/public/")
                ? publicChat
                : restricted;
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
