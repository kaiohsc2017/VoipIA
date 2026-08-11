package com.asteriskia.domain.callcenter.flow.engine;

import java.time.Duration;
import java.util.List;

/**
 * ChannelDriver — abstrai o meio de comunicação com o "cliente" de uma execução de fluxo, para
 * que {@link FlowExecutionEngine} seja agnóstico de canal (Fase 5, decisão de arquitetura). Nesta
 * sub-fase (5b) a única implementação é {@link AriVoiceChannelDriver} (voz real via ARI) — o
 * simulador (5c) e o canal de chat (Fase 7) implementam a mesma interface depois, sem mudar o
 * motor.
 */
public interface ChannelDriver {

    /** Toca uma mensagem (áudio, TTS ou texto, conforme a implementação) e retorna quando termina. */
    void playMessage(String audioPath, String text);

    /** Aguarda uma escolha do usuário entre os dígitos/opções válidos, ou timeout/desistência. */
    PromptResult promptChoice(List<String> validChoices, Duration timeout);

    void setVariable(String name, String value);

    String getVariable(String name);

    /** Encaminha o canal para a fila informada (ex.: via dialplan) — a execução do fluxo termina aqui. */
    void transferToQueue(String queueExtension);

    /** Encerra o canal (Hangup) — a execução do fluxo termina aqui. */
    void end();

    /** Resultado de {@link #promptChoice}. */
    record PromptResult(Outcome outcome, String choice) {
        public enum Outcome {
            CHOSEN,
            TIMEOUT,
            HUNG_UP
        }

        public static PromptResult chosen(String choice) {
            return new PromptResult(Outcome.CHOSEN, choice);
        }

        public static PromptResult timeout() {
            return new PromptResult(Outcome.TIMEOUT, null);
        }

        public static PromptResult hungUp() {
            return new PromptResult(Outcome.HUNG_UP, null);
        }
    }
}
