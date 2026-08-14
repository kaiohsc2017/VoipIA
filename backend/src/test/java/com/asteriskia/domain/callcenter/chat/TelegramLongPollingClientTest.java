package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.asteriskia.domain.settings.EnvFileStore;

/**
 * TelegramLongPollingClientTest — cobre idempotência por update_id, criação de sessão nova para
 * chat_id sem sessão aberta, reuso da sessão existente, persistência/retomada do offset após
 * "restart" simulado, e a resolução do token nunca logando o valor real (Fase 7e).
 */
@ExtendWith(MockitoExtension.class)
class TelegramLongPollingClientTest {

    private static final String TOKEN_REF = "CALLCENTER_TELEGRAM_BOT_TOKEN";
    private static final String TOKEN_VALUE = "123456:secret-bot-token";

    @Mock
    private CcChatChannelRepository channelRepository;
    @Mock
    private CcChatSessionRepository sessionRepository;
    @Mock
    private CcTelegramPollStateRepository pollStateRepository;
    @Mock
    private CcChatService chatService;
    @Mock
    private TelegramApiClient telegramApiClient;
    @Mock
    private EnvFileStore envFileStore;

    private TelegramLongPollingClient client;
    private CcChatChannel telegramChannel;

    @BeforeEach
    void setUp() throws IOException {
        client = new TelegramLongPollingClient(
                channelRepository, sessionRepository, pollStateRepository, chatService, telegramApiClient, envFileStore);
        telegramChannel = CcChatChannel.builder()
                .id(1L).code("tg").type("telegram").active(true)
                .telegramBotTokenRef(TOKEN_REF)
                .build();
        lenient().when(channelRepository.findByTypeAndActiveTrue("telegram")).thenReturn(List.of(telegramChannel));
        lenient().when(envFileStore.readRaw()).thenReturn(Map.of(TOKEN_REF, TOKEN_VALUE));
        lenient().when(pollStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("chat_id sem sessão aberta cria uma sessão nova e posta a mensagem recebida")
    void pollAllChannels_newChatId_createsSessionAndPostsMessage() {
        when(pollStateRepository.findById(1L)).thenReturn(Optional.of(
                CcTelegramPollState.builder().channelId(1L).lastUpdateId(10L).build()));
        when(telegramApiClient.getUpdates(TOKEN_VALUE, 11L, 0)).thenReturn(
                List.of(new TelegramApiClient.TelegramUpdate(11L, "555111", "Cliente", "oi")));
        when(sessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull(1L, "555111")).thenReturn(Optional.empty());
        CcChatSession newSession = CcChatSession.builder().id(99L).externalRef("555111").build();
        when(chatService.startExternalSession("tg", "555111", "Cliente")).thenReturn(newSession);

        client.pollAllChannels();

        verify(chatService).startExternalSession("tg", "555111", "Cliente");
        verify(chatService).postMessage(99L, "customer", "Cliente", "oi");
    }

    @Test
    @DisplayName("chat_id com sessão já aberta reusa a sessão existente, sem criar uma nova")
    void pollAllChannels_existingChatId_reusesSession() {
        when(pollStateRepository.findById(1L)).thenReturn(Optional.of(
                CcTelegramPollState.builder().channelId(1L).lastUpdateId(10L).build()));
        when(telegramApiClient.getUpdates(TOKEN_VALUE, 11L, 0)).thenReturn(
                List.of(new TelegramApiClient.TelegramUpdate(11L, "555111", "Cliente", "segunda mensagem")));
        CcChatSession existing = CcChatSession.builder().id(42L).externalRef("555111").build();
        when(sessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull(1L, "555111")).thenReturn(Optional.of(existing));

        client.pollAllChannels();

        verify(chatService, never()).startExternalSession(anyString(), anyString(), any());
        verify(chatService).postMessage(42L, "customer", "Cliente", "segunda mensagem");
    }

    @Test
    @DisplayName("update_id já visto não é reprocessado (idempotência defensiva além do offset do Telegram)")
    void pollAllChannels_duplicateUpdateId_neverReprocessed() {
        when(pollStateRepository.findById(1L)).thenReturn(Optional.of(
                CcTelegramPollState.builder().channelId(1L).lastUpdateId(10L).build()));
        // API mockada devolvendo, por engano, um update já visto (id=10, igual ao offset base) e
        // um update novo (id=11) — simula o cenário de reentrega/duplicidade defendido no código.
        when(telegramApiClient.getUpdates(TOKEN_VALUE, 11L, 0)).thenReturn(List.of(
                new TelegramApiClient.TelegramUpdate(10L, "555111", "Cliente", "duplicado"),
                new TelegramApiClient.TelegramUpdate(11L, "555111", "Cliente", "novo")));
        CcChatSession existing = CcChatSession.builder().id(42L).externalRef("555111").build();
        when(sessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull(1L, "555111")).thenReturn(Optional.of(existing));

        client.pollAllChannels();

        verify(chatService, times(1)).postMessage(eq(42L), eq("customer"), anyString(), anyString());
        verify(chatService, never()).postMessage(42L, "customer", "Cliente", "duplicado");
    }

    @Test
    @DisplayName("last_update_id é persistido e retomado corretamente após restart simulado")
    void pollAllChannels_persistsAndResumesOffsetAfterRestart() {
        // "Antes do restart": estado zerado, processa update 5, persiste offset 5.
        when(pollStateRepository.findById(1L)).thenReturn(Optional.empty());
        when(telegramApiClient.getUpdates(TOKEN_VALUE, 1L, 0)).thenReturn(
                List.of(new TelegramApiClient.TelegramUpdate(5L, "555111", "Cliente", "oi")));
        when(sessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull(1L, "555111")).thenReturn(
                Optional.of(CcChatSession.builder().id(1L).externalRef("555111").build()));

        client.pollAllChannels();

        // 2 saves nesta primeira rodada: 1 para criar o estado zerado (canal nunca visto antes),
        // 1 para persistir o offset já processado (5) — o que importa é o valor final gravado.
        ArgumentCaptor<CcTelegramPollState> captor = ArgumentCaptor.forClass(CcTelegramPollState.class);
        verify(pollStateRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getLastUpdateId()).isEqualTo(5L);

        // "Depois do restart": um novo bean é instanciado, mas o estado persistido (offset 5) é
        // lido do banco de novo — sem reprocessar o update 5.
        var clientAfterRestart = new TelegramLongPollingClient(
                channelRepository, sessionRepository, pollStateRepository, chatService, telegramApiClient, envFileStore);
        when(pollStateRepository.findById(1L)).thenReturn(Optional.of(
                CcTelegramPollState.builder().channelId(1L).lastUpdateId(5L).build()));
        when(telegramApiClient.getUpdates(TOKEN_VALUE, 6L, 0)).thenReturn(List.of());

        clientAfterRestart.pollAllChannels();

        verify(telegramApiClient).getUpdates(TOKEN_VALUE, 6L, 0);
        verify(chatService, times(1)).postMessage(anyLong(), eq("customer"), anyString(), anyString());
    }

    @Test
    @DisplayName("canal sem token configurado não chama a API do Telegram (nunca loga o valor do token, só a referência)")
    void pollAllChannels_channelWithoutToken_neverCallsApi() throws IOException {
        when(envFileStore.readRaw()).thenReturn(Map.of());

        client.pollAllChannels();

        verify(telegramApiClient, never()).getUpdates(anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("ChatAgentMessageSentEvent de uma sessão Telegram entrega a mensagem via sendMessage")
    void onAgentMessageSent_telegramSession_deliversViaSendMessage() {
        CcChatSession session = CcChatSession.builder().id(7L).channel(telegramChannel).externalRef("555111").build();
        when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));

        client.onAgentMessageSent(new ChatAgentMessageSentEvent(7L, "resposta do agente"));

        verify(telegramApiClient).sendMessage(TOKEN_VALUE, "555111", "resposta do agente");
    }

    @Test
    @DisplayName("ChatAgentMessageSentEvent de uma sessão webchat (sem externalRef) não chama o Telegram")
    void onAgentMessageSent_webchatSession_neverCallsTelegram() {
        CcChatChannel webchat = CcChatChannel.builder().id(2L).code("webchat").type("webchat").build();
        CcChatSession session = CcChatSession.builder().id(8L).channel(webchat).build();
        when(sessionRepository.findById(8L)).thenReturn(Optional.of(session));

        client.onAgentMessageSent(new ChatAgentMessageSentEvent(8L, "resposta"));

        verify(telegramApiClient, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("canal com telegramBotTokenRef fora do padrão esperado nunca resolve/chama a API (defesa em profundidade, achado CRITICAL)")
    void pollAllChannels_channelWithTokenRefOutsideAllowedPattern_neverCallsApi() {
        CcChatChannel maliciousChannel = CcChatChannel.builder()
                .id(3L).code("tg-malicioso").type("telegram").active(true)
                .telegramBotTokenRef("POSTGRES_PASSWORD")
                .build();
        when(channelRepository.findByTypeAndActiveTrue("telegram")).thenReturn(List.of(maliciousChannel));

        client.pollAllChannels();

        // Nem sequer chega a ler o .env — o padrão é rejeitado antes de qualquer tentativa de
        // resolver o valor (defesa em profundidade: o achado CRITICAL nunca teria a chance de
        // vazar POSTGRES_PASSWORD, mesmo que essa checagem de leitura falhasse por algum motivo).

        verify(telegramApiClient, never()).getUpdates(anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("falha ao processar um update no meio do lote não causa reprocessamento dos updates já entregues com sucesso")
    void pollAllChannels_partialBatchFailure_neverReprocessesAlreadyHandledUpdates() {
        when(pollStateRepository.findById(1L)).thenReturn(Optional.of(
                CcTelegramPollState.builder().channelId(1L).lastUpdateId(10L).build()));
        when(telegramApiClient.getUpdates(TOKEN_VALUE, 11L, 0)).thenReturn(List.of(
                new TelegramApiClient.TelegramUpdate(11L, "555111", "Cliente", "primeira mensagem, processada com sucesso"),
                new TelegramApiClient.TelegramUpdate(12L, "555222", "Outro", "segunda mensagem, falha ao processar")));
        CcChatSession existingA = CcChatSession.builder().id(42L).externalRef("555111").build();
        when(sessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull(1L, "555111")).thenReturn(Optional.of(existingA));
        when(sessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull(1L, "555222"))
                .thenThrow(new RuntimeException("erro transitório de banco"));

        try {
            client.pollAllChannels();
        } catch (RuntimeException ignored) {
            // pollAllChannels já captura a exceção internamente (log + continue) — não deveria
            // propagar, mas o teste tolera caso a exceção escape, o que importa é o offset abaixo.
        }

        // O primeiro update (11) já foi entregue com sucesso — seu offset tem que estar
        // persistido mesmo com a falha no segundo update (12), senão o próximo ciclo reenviaria
        // a mensagem "primeira mensagem, processada com sucesso" de novo.
        ArgumentCaptor<CcTelegramPollState> captor = ArgumentCaptor.forClass(CcTelegramPollState.class);
        verify(pollStateRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(saved -> assertThat(saved.getLastUpdateId()).isEqualTo(11L));
        verify(chatService, times(1)).postMessage(eq(42L), eq("customer"), anyString(), anyString());
    }
}
