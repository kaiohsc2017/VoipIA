package com.asteriskia.domain.callcenter.flow.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver.PromptResult;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver.RecordResult;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver.TextResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SimulatedChannelDriverTest — cobre o driver simulado do dry-run (Fase 5d): nunca fala com
 * Asterisk/ARI/chat real, consome as respostas simuladas em ordem, e nunca deixa vazar erro se a
 * fila de respostas se esgotar no meio do fluxo (mesmo comportamento seguro dos drivers reais).
 */
class SimulatedChannelDriverTest {

    @Test
    @DisplayName("promptChoice consome a próxima resposta simulada, na ordem")
    void promptChoice_consumesInOrder() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of("1", "2"));

        var first = driver.promptChoice(List.of("1", "2", "3"), Duration.ofSeconds(5));
        var second = driver.promptChoice(List.of("1", "2", "3"), Duration.ofSeconds(5));

        assertThat(first).isEqualTo(PromptResult.chosen("1"));
        assertThat(second).isEqualTo(PromptResult.chosen("2"));
    }

    @Test
    @DisplayName("promptChoice sem resposta simulada disponível devolve timeout, nunca lança")
    void promptChoice_noScriptedResponse_timesOut() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of());

        var result = driver.promptChoice(List.of("1"), Duration.ofSeconds(5));

        assertThat(result).isEqualTo(PromptResult.timeout());
    }

    @Test
    @DisplayName("promptChoice com resposta fora das opções válidas devolve INVALID")
    void promptChoice_invalidResponse() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of("9"));

        var result = driver.promptChoice(List.of("1", "2"), Duration.ofSeconds(5));

        assertThat(result.outcome()).isEqualTo(PromptResult.Outcome.INVALID);
        assertThat(result.choice()).isEqualTo("9");
    }

    @Test
    @DisplayName("collectText consome resposta simulada e recordResponse idem")
    void collectTextAndRecordResponse_consumeScriptedInput() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of("texto livre", "audio-resp"));

        var text = driver.collectText(Duration.ofSeconds(5));
        var recorded = driver.recordResponse(Duration.ofSeconds(5));

        assertThat(text).isEqualTo(TextResult.collected("texto livre"));
        assertThat(recorded.outcome()).isEqualTo(RecordResult.Outcome.RECORDED);
        assertThat(recorded.audioPath()).startsWith("simulado://");
    }

    @Test
    @DisplayName("collectText/recordResponse sem entrada simulada nunca lançam exceção")
    void collectTextAndRecordResponse_noScriptedInput_neverThrow() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of());

        assertThat(driver.collectText(Duration.ofSeconds(1))).isEqualTo(TextResult.timeout());
        assertThat(driver.recordResponse(Duration.ofSeconds(1))).isEqualTo(RecordResult.hungUp());
    }

    @Test
    @DisplayName("setVariable/getVariable e variáveis iniciais funcionam em memória, sem persistência")
    void variables_workInMemory() {
        var driver = new SimulatedChannelDriver(Map.of("nome", "Fulano"), List.of());

        assertThat(driver.getVariable("nome")).isEqualTo("Fulano");
        driver.setVariable("saldo", "100");
        assertThat(driver.getVariable("saldo")).isEqualTo("100");
        assertThat(driver.variablesSnapshot()).containsEntry("nome", "Fulano").containsEntry("saldo", "100");
    }

    @Test
    @DisplayName("transferToQueue e end marcam o fim da simulação e registram no roteiro")
    void transferAndEnd_markEndedAndLog() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of());

        driver.transferToQueue("5001");

        assertThat(driver.isEnded()).isTrue();
        assertThat(driver.transferredQueue()).isEqualTo("5001");
        assertThat(driver.eventLog()).isNotEmpty();

        var driver2 = new SimulatedChannelDriver(Map.of(), List.of());
        driver2.end();
        assertThat(driver2.isEnded()).isTrue();
        assertThat(driver2.transferredQueue()).isNull();
    }

    @Test
    @DisplayName("transferToExtension (Fase 5e.2) marca o fim da simulação e registra o ramal no roteiro")
    void transferToExtension_marksEndedAndLogsExtension() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of());

        driver.transferToExtension("4001");

        assertThat(driver.isEnded()).isTrue();
        assertThat(driver.transferredExtension()).isEqualTo("4001");
        assertThat(driver.eventLog()).anySatisfy(e -> assertThat(e).contains("4001"));
    }

    @Test
    @DisplayName("playMessage registra no roteiro, mas ignora chamada vazia (sem áudio nem texto)")
    void playMessage_logsOrIgnoresBlank() {
        var driver = new SimulatedChannelDriver(Map.of(), List.of());

        driver.playMessage(null, "Olá, tudo bem?");
        driver.playMessage(null, null);

        assertThat(driver.eventLog()).hasSize(1).first().asString().contains("Olá, tudo bem?");
    }
}
