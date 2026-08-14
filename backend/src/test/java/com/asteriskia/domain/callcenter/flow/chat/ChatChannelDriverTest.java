package com.asteriskia.domain.callcenter.flow.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.asteriskia.domain.callcenter.chat.CcChatService;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a implementação de {@link ChannelDriver} para chat (Fase 24) — a prova real de que o
 * motor de fluxo é agnóstico de canal: os mesmos contratos (playMessage/promptChoice/
 * transferToQueue/end) que {@code AriVoiceChannelDriver} atende via ARI, este atende via
 * {@code CcChatService}.
 */
@ExtendWith(MockitoExtension.class)
class ChatChannelDriverTest {

    @Mock
    private CcChatService chatService;

    private ChatChannelDriver driver;

    @BeforeEach
    void setUp() {
        driver = new ChatChannelDriver(chatService, 5L);
    }

    @Test
    @DisplayName("playMessage com texto envia mensagem de bot; sem texto (só áudio) não envia nada")
    void playMessage_onlyWithText() {
        driver.playMessage(null, "Olá!");
        verify(chatService).postBotMessage(5L, "Olá!");

        driver.playMessage("algum-audio-sem-sentido-em-chat", null);
        verify(chatService, never()).postBotMessage(5L, null);
    }

    @Test
    @DisplayName("promptChoice retorna CHOSEN quando a próxima mensagem do cliente é uma opção válida")
    void promptChoice_validChoice_returnsChosen() {
        // Mensagem já na fila antes de aguardar — BlockingQueue.poll() a devolve na primeira
        // iteração do loop, sem depender de temporização entre threads (não-flaky).
        driver.onCustomerMessage("2");
        var result = driver.promptChoice(List.of("1", "2", "3"), Duration.ofSeconds(3));

        assertThat(result.outcome()).isEqualTo(ChannelDriver.PromptResult.Outcome.CHOSEN);
        assertThat(result.choice()).isEqualTo("2");
    }

    @Test
    @DisplayName("promptChoice retorna INVALID quando a mensagem não é uma das opções válidas")
    void promptChoice_invalidChoice_returnsInvalid() {
        driver.onCustomerMessage("9");
        var result = driver.promptChoice(List.of("1", "2"), Duration.ofSeconds(2));

        assertThat(result.outcome()).isEqualTo(ChannelDriver.PromptResult.Outcome.INVALID);
    }

    @Test
    @DisplayName("promptChoice retorna TIMEOUT quando nenhuma mensagem chega dentro do prazo")
    void promptChoice_noMessage_returnsTimeout() {
        var result = driver.promptChoice(List.of("1", "2"), Duration.ofMillis(600));

        assertThat(result.outcome()).isEqualTo(ChannelDriver.PromptResult.Outcome.TIMEOUT);
    }

    @Test
    @DisplayName("collectText devolve o texto livre da próxima mensagem, sem restringir a opções")
    void collectText_returnsFreeText() {
        driver.onCustomerMessage("meu email é fulano@empresa.com");
        var result = driver.collectText(Duration.ofSeconds(2));

        assertThat(result.outcome()).isEqualTo(ChannelDriver.TextResult.Outcome.COLLECTED);
        assertThat(result.text()).isEqualTo("meu email é fulano@empresa.com");
    }

    @Test
    @DisplayName("transferToQueue delega pro CcChatService.transferToHumanQueue e encerra o loop de espera")
    void transferToQueue_delegatesAndEnds() {
        driver.transferToQueue("5001");

        verify(chatService).transferToHumanQueue(5L, "5001");
        assertThat(driver.promptChoice(List.of("1"), Duration.ofSeconds(1)).outcome())
                .isEqualTo(ChannelDriver.PromptResult.Outcome.HUNG_UP);
    }

    @Test
    @DisplayName("end delega pro CcChatService.closeByBot e encerra o loop de espera")
    void end_delegatesAndEnds() {
        driver.end();

        verify(chatService).closeByBot(5L);
        assertThat(driver.collectText(Duration.ofSeconds(1)).outcome())
                .isEqualTo(ChannelDriver.TextResult.Outcome.HUNG_UP);
    }

    @Test
    @DisplayName("transferToExtension lança UnsupportedOperationException — chat não tem ramal SIP")
    void transferToExtension_throwsUnsupported() {
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> driver.transferToExtension("4001"));
    }

    @Test
    @DisplayName("setVariable/getVariable guardam em memória local, sem depender de canal externo")
    void setVariableAndGetVariable_localState() {
        driver.setVariable("nome", "Maria");

        assertThat(driver.getVariable("nome")).isEqualTo("Maria");
    }
}
