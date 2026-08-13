package com.asteriskia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cobre a decisão de CORS da rota de chat interno (Fase 24) — origem "*" (Fase 7b) trocada por
 * uma lista configurável, fail-closed quando vazia. Reflection para injetar os {@code @Value}
 * porque {@code AppConfig} não é um {@code @SpringBootTest} (evita subir o contexto todo só pra
 * testar uma decisão de branch por path).
 */
class AppConfigTest {

    private AppConfig configWith(String chatAllowedOrigins) throws Exception {
        var config = new AppConfig();
        setField(config, "allowedOrigins", "https://app.voiphash.com.br");
        setField(config, "chatAllowedOrigins", chatAllowedOrigins);
        return config;
    }

    private void setField(AppConfig config, String name, String value) throws Exception {
        Field field = AppConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }

    private HttpServletRequest requestFor(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    @DisplayName("origem vazia (default desta VPS de dev) não libera nenhuma origem cross-origin na rota de chat")
    void chatRoute_withEmptyAllowedOrigins_allowsNone() throws Exception {
        var source = configWith("").corsConfigurationSource();
        var config = source.getCorsConfiguration(requestFor("/api/v1/callcenter/chat/public/sessions"));

        assertThat(config.getAllowedOriginPatterns()).isEmpty();
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("origens configuradas são aplicadas só na rota de chat, nunca combinadas com a regra geral")
    void chatRoute_withConfiguredOrigins_allowsOnlyThose() throws Exception {
        var source = configWith("https://intranet.empresa.com.br,https://outra.empresa.com.br").corsConfigurationSource();
        var chatConfig = source.getCorsConfiguration(requestFor("/api/v1/callcenter/chat/public/sessions/1/messages"));
        var otherConfig = source.getCorsConfiguration(requestFor("/api/v1/callcenter/uras"));

        assertThat(chatConfig.getAllowedOriginPatterns())
                .containsExactly("https://intranet.empresa.com.br", "https://outra.empresa.com.br");
        // A regra geral nunca deve herdar as origens do chat — são duas CorsConfiguration
        // inteiras e separadas (lição da Fase 7b: nunca combinar duas configs parciais).
        assertThat(otherConfig.getAllowedOriginPatterns()).containsExactly("https://app.voiphash.com.br");
        assertThat(otherConfig.getAllowCredentials()).isTrue();
    }

    @Test
    @DisplayName("espaço em volta da vírgula na lista de origens é ignorado")
    void chatRoute_withWhitespaceAroundCommas_trimsEachOrigin() throws Exception {
        var source = configWith(" https://intranet.empresa.com.br , https://outra.empresa.com.br ").corsConfigurationSource();
        var chatConfig = source.getCorsConfiguration(requestFor("/api/v1/callcenter/chat/public/sessions"));

        assertThat(chatConfig.getAllowedOriginPatterns())
                .containsExactly("https://intranet.empresa.com.br", "https://outra.empresa.com.br");
    }
}
