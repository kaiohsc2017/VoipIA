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

    /** Grava a resposta falada/comentário do usuário (Fase 21 — pesquisa de satisfação, modos
     * FALADA_IA/DTMF_COMENTARIO), até {@code maxDuration} ou até o cliente encerrar com {@code #}.
     * {@code audioPath} do resultado é absoluto e já resolvido — {@code null} se nada foi
     * capturado (desistência/hangup antes de começar a falar). */
    RecordResult recordResponse(Duration maxDuration);

    /** Aguarda um texto livre do usuário (nó "coletar_texto", Fase 24 — canal chat). Sem
     * equivalente em voz nesta fase (coleta de texto falado é escopo da Fase 14/coletar_entrada,
     * ainda não implementada) — implementações de voz podem lançar {@link UnsupportedOperationException}. */
    TextResult collectText(Duration timeout);

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
            HUNG_UP,
            /** Dígito recebido, mas fora de {@code validChoices} (Fase 5c — antes descartado em
             * silêncio; agora devolvido para o handler decidir repetir/avisar). */
            INVALID
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

        public static PromptResult invalid(String choice) {
            return new PromptResult(Outcome.INVALID, choice);
        }
    }

    /** Resultado de {@link #collectText}. */
    record TextResult(Outcome outcome, String text) {
        public enum Outcome {
            COLLECTED,
            TIMEOUT,
            HUNG_UP
        }

        public static TextResult collected(String text) {
            return new TextResult(Outcome.COLLECTED, text);
        }

        public static TextResult timeout() {
            return new TextResult(Outcome.TIMEOUT, null);
        }

        public static TextResult hungUp() {
            return new TextResult(Outcome.HUNG_UP, null);
        }
    }

    /** Resultado de {@link #recordResponse}. */
    record RecordResult(Outcome outcome, String audioPath) {
        public enum Outcome {
            RECORDED,
            HUNG_UP
        }

        public static RecordResult recorded(String audioPath) {
            return new RecordResult(Outcome.RECORDED, audioPath);
        }

        public static RecordResult hungUp() {
            return new RecordResult(Outcome.HUNG_UP, null);
        }
    }
}
