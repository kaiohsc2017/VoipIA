package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import org.junit.jupiter.api.Test;

/** Cobre a regra de precedência (D5, confirmada com o usuário): agente nulo/zerado usa o limite
 * da fila; qualquer valor {@code > 0} do agente sempre prevalece sobre o da fila. */
class ChatBlendingServiceTest {

    private final ChatBlendingService service = new ChatBlendingService();

    private CcAgent agentWithLimit(Integer limit) {
        CcAgent agent = new CcAgent();
        agent.setMaxConcurrentChats(limit);
        return agent;
    }

    private CcQueue queueWithLimit(Integer limit) {
        CcQueue queue = new CcQueue();
        queue.setMaxConcurrentChats(limit);
        return queue;
    }

    @Test
    void agenteNulo_usaLimiteDaFila() {
        assertThat(service.resolveLimit(agentWithLimit(null), queueWithLimit(3))).isEqualTo(3);
    }

    @Test
    void agenteZerado_usaLimiteDaFila() {
        assertThat(service.resolveLimit(agentWithLimit(0), queueWithLimit(3))).isEqualTo(3);
    }

    @Test
    void agenteComValor_prevaleceSobreAFila() {
        assertThat(service.resolveLimit(agentWithLimit(1), queueWithLimit(10))).isEqualTo(1);
    }

    @Test
    void agenteComValor_prevaleceMesmoSemLimiteNaFila() {
        assertThat(service.resolveLimit(agentWithLimit(2), queueWithLimit(null))).isEqualTo(2);
    }

    @Test
    void semLimiteNoAgenteNemNaFila_retornaNulo() {
        assertThat(service.resolveLimit(agentWithLimit(null), queueWithLimit(null))).isNull();
    }

    @Test
    void agenteZeradoESemLimiteNaFila_retornaNulo() {
        assertThat(service.resolveLimit(agentWithLimit(0), queueWithLimit(null))).isNull();
    }
}
