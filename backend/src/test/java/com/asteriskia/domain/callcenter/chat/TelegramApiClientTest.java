package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * TelegramApiClientTest — cobre a exigência de segurança central da Fase 7e: o token do bot é
 * exigido pela própria API do Telegram no path da URL (sem alternativa de header oficial), então
 * qualquer exceção/log tem que nunca incluir a URI completa nem o token — só o nome da classe da
 * exceção (mesma disciplina já usada para a API key do Gemini).
 */
@ExtendWith(MockitoExtension.class)
class TelegramApiClientTest {

    private static final String SECRET_TOKEN = "123456:AAExampleSecretBotTokenAbc";

    @Mock
    private WebClient.Builder webClientBuilder;

    private TelegramApiClient client;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        client = new TelegramApiClient(webClientBuilder);
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(TelegramApiClient.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(TelegramApiClient.class)).detachAppender(logAppender);
    }

    @Test
    @DisplayName("getUpdates nunca propaga o token em exceção nem em log quando a chamada HTTP falha")
    void getUpdates_httpFailure_neverLeaksTokenInExceptionOrLog() {
        // Simula uma falha cuja mensagem (como WebClientResponseException.getMessage() faria)
        // incluiria a URI completa — inclusive o token — se não fosse tratada com disciplina.
        when(webClientBuilder.baseUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("GET request for \"https://api.telegram.org/bot" + SECRET_TOKEN
                        + "/getUpdates?offset=1\" resulted in 401 Unauthorized"));

        List<TelegramApiClient.TelegramUpdate> result = client.getUpdates(SECRET_TOKEN, 1, 0);

        assertThat(result).isEmpty();
        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET_TOKEN);
        });
    }

    @Test
    @DisplayName("sendMessage nunca propaga exceção nem vaza o token em log quando a chamada HTTP falha")
    void sendMessage_httpFailure_neverLeaksTokenInExceptionOrLog() {
        when(webClientBuilder.baseUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("POST request for \"https://api.telegram.org/bot" + SECRET_TOKEN
                        + "/sendMessage\" resulted in 403 Forbidden"));

        client.sendMessage(SECRET_TOKEN, "555111", "oi");

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET_TOKEN);
        });
    }
}
