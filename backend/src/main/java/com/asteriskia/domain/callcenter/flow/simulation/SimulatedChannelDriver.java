package com.asteriskia.domain.callcenter.flow.simulation;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SimulatedChannelDriver — implementação de {@link ChannelDriver} para o simulador de fluxo (Fase
 * 5d, dry-run). Nunca fala com Asterisk/ARI/chat real: toda entrada do "cliente" simulado é
 * fornecida antecipadamente pelo operador ({@code respostasSimuladas}, consumida em ordem) e toda
 * saída (mensagem tocada, transferência, encerramento) só é registrada num log em memória — nada é
 * persistido.
 */
public class SimulatedChannelDriver implements ChannelDriver {

    private final Deque<String> respostasSimuladas;
    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final List<String> eventLog = new ArrayList<>();
    private boolean ended = false;
    private String transferredQueue;

    public SimulatedChannelDriver(Map<String, String> initialVariables, List<String> respostasSimuladas) {
        this.variables.putAll(initialVariables == null ? Map.of() : initialVariables);
        this.respostasSimuladas = new ArrayDeque<>(respostasSimuladas == null ? List.of() : respostasSimuladas);
    }

    /** Roteiro de eventos desta simulação, na ordem em que ocorreram — nunca persistido. */
    public List<String> eventLog() {
        return List.copyOf(eventLog);
    }

    public Map<String, String> variablesSnapshot() {
        return Map.copyOf(variables);
    }

    public boolean isEnded() {
        return ended;
    }

    public String transferredQueue() {
        return transferredQueue;
    }

    @Override
    public void playMessage(String audioPath, String text) {
        if ((text == null || text.isBlank()) && (audioPath == null || audioPath.isBlank())) {
            return;
        }
        eventLog.add("Mensagem tocada: " + (text != null && !text.isBlank() ? text : ("áudio " + audioPath)));
    }

    @Override
    public PromptResult promptChoice(List<String> validChoices, Duration timeout) {
        var resposta = respostasSimuladas.poll();
        if (resposta == null) {
            eventLog.add("Menu sem resposta simulada disponível — timeout.");
            return PromptResult.timeout();
        }
        if (validChoices.contains(resposta)) {
            eventLog.add("Opção escolhida (simulada): " + resposta);
            return PromptResult.chosen(resposta);
        }
        eventLog.add("Opção inválida (simulada): " + resposta);
        return PromptResult.invalid(resposta);
    }

    @Override
    public RecordResult recordResponse(Duration maxDuration) {
        var resposta = respostasSimuladas.poll();
        if (resposta == null) {
            eventLog.add("Gravação de resposta sem entrada simulada — desistência.");
            return RecordResult.hungUp();
        }
        eventLog.add("Resposta gravada (simulada): " + resposta);
        return RecordResult.recorded("simulado://" + resposta);
    }

    @Override
    public TextResult collectText(Duration timeout) {
        var resposta = respostasSimuladas.poll();
        if (resposta == null) {
            eventLog.add("Coleta de texto sem entrada simulada — timeout.");
            return TextResult.timeout();
        }
        eventLog.add("Texto coletado (simulado): " + resposta);
        return TextResult.collected(resposta);
    }

    @Override
    public void setVariable(String name, String value) {
        variables.put(name, value);
        eventLog.add("Variável definida: " + name + "=" + value);
    }

    @Override
    public String getVariable(String name) {
        return variables.get(name);
    }

    @Override
    public void transferToQueue(String queueExtension) {
        ended = true;
        transferredQueue = queueExtension;
        eventLog.add("Transferido para a fila (simulado): " + queueExtension);
    }

    @Override
    public void end() {
        ended = true;
        eventLog.add("Chamada/sessão encerrada (simulado).");
    }
}
