package com.asteriskia.domain.callcenter.flow.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.asteriskia.domain.callcenter.chat.CcChatService;
import com.asteriskia.domain.callcenter.chat.ChatBotSessionStartedEvent;
import com.asteriskia.domain.callcenter.chat.ChatCustomerMessageReceivedEvent;
import com.asteriskia.domain.callcenter.chat.ChatSessionEndedEvent;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionEngine;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a classe mais sensível da Fase 24 (ponte por evento entre o domínio de chat e o motor de
 * fluxo): a thread daemon por sessão, o cleanup do {@code Map<Long, ChatChannelDriver>} em todo
 * caminho (sucesso e exceção), e o roteamento silencioso dos eventos de mensagem/encerramento
 * quando não há bot em execução para a sessão. Usa reflection sobre o campo privado
 * {@code driversBySessionId} (mesmo padrão de acesso a campo privado já usado em
 * {@code AppConfigTest}) — não há acessor de produção só para isto porque nada externo à classe
 * precisa dele.
 */
@ExtendWith(MockitoExtension.class)
class ChatFlowLauncherServiceTest {

    @Mock
    private CcChatService chatService;

    @Mock
    private FlowExecutionEngine flowExecutionEngine;

    private ChatFlowLauncherService service;

    @BeforeEach
    void setUp() {
        service = new ChatFlowLauncherService(chatService, flowExecutionEngine);
    }

    @Test
    @DisplayName("onBotSessionStarted dispara o motor de fluxo numa thread separada e remove o driver do mapa após sucesso")
    void onBotSessionStarted_success_cleansUpDriverAfterCompletion() throws Exception {
        var latch = new CountDownLatch(1);
        doAnswer(inv -> {
                    latch.countDown();
                    return null;
                })
                .when(flowExecutionEngine)
                .startForFlow(eq(10L), anyString(), anyString(), any());

        service.onBotSessionStarted(new ChatBotSessionStartedEvent(5L, 10L));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        awaitMapSize(0);
        verify(flowExecutionEngine).startForFlow(eq(10L), anyString(), anyString(), any(ChatChannelDriver.class));
    }

    @Test
    @DisplayName("onBotSessionStarted remove o driver do mapa mesmo quando o motor de fluxo lança exceção")
    void onBotSessionStarted_flowThrows_stillCleansUpDriver() throws Exception {
        var latch = new CountDownLatch(1);
        doAnswer(inv -> {
                    latch.countDown();
                    throw new RuntimeException("falha simulada");
                })
                .when(flowExecutionEngine)
                .startForFlow(any(), anyString(), anyString(), any());

        service.onBotSessionStarted(new ChatBotSessionStartedEvent(5L, 10L));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        awaitMapSize(0);
    }

    @Test
    @DisplayName("onCustomerMessage repassa o texto ao driver registrado da sessão")
    void onCustomerMessage_deliversToRegisteredDriver() throws Exception {
        var driver = mock(ChatChannelDriver.class);
        registerDriver(5L, driver);

        service.onCustomerMessage(new ChatCustomerMessageReceivedEvent(5L, "oi"));

        verify(driver).onCustomerMessage("oi");
    }

    @Test
    @DisplayName("onCustomerMessage é silenciosamente ignorado quando não há bot em execução para a sessão")
    void onCustomerMessage_noDriverRegistered_isNoOp() {
        assertThatCode(() -> service.onCustomerMessage(new ChatCustomerMessageReceivedEvent(999L, "oi")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onSessionEnded destrava o driver registrado da sessão")
    void onSessionEnded_deliversToRegisteredDriver() throws Exception {
        var driver = mock(ChatChannelDriver.class);
        registerDriver(5L, driver);

        service.onSessionEnded(new ChatSessionEndedEvent(5L));

        verify(driver).onSessionEnded();
    }

    @Test
    @DisplayName("onSessionEnded é silenciosamente ignorado quando não há bot em execução para a sessão")
    void onSessionEnded_noDriverRegistered_isNoOp() {
        assertThatCode(() -> service.onSessionEnded(new ChatSessionEndedEvent(999L)))
                .doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ChatChannelDriver> driversMap() throws Exception {
        Field field = ChatFlowLauncherService.class.getDeclaredField("driversBySessionId");
        field.setAccessible(true);
        return (Map<Long, ChatChannelDriver>) field.get(service);
    }

    private void registerDriver(Long sessionId, ChatChannelDriver driver) throws Exception {
        driversMap().put(sessionId, driver);
    }

    private void awaitMapSize(int expectedSize) throws Exception {
        var deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && driversMap().size() != expectedSize) {
            Thread.sleep(20);
        }
        assertThat(driversMap()).hasSize(expectedSize);
    }
}
