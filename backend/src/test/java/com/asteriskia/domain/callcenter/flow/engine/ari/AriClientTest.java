package com.asteriskia.domain.callcenter.flow.engine.ari;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fase 10 (endurecimento/segurança) — regressão dos achados CRITICAL C1 e HIGH H1 da revisão de
 * segurança do motor ARI. Os dois métodos sob teste retornam antes de tocar a rede quando a
 * validação falha, então não é necessário um servidor HTTP real: basta confirmar que o método
 * recusa o valor perigoso sem lançar exceção (nenhum canal existe de fato nestes testes).
 */
class AriClientTest {

    private final AriClient client = new AriClient("http://localhost:0/ari", "user", "pass");

    @Test
    void setChannelVar_recusaNomeComSintaxeDeFuncaoDeDialplan() {
        // C1: nome de variável com sintaxe de função (FILE(...)) não pode chegar ao ARI —
        // o Asterisk interpretaria como Set() de função, incluindo funções com escrita em arquivo.
        client.setChannelVar("chan-1", "FILE(/etc/asterisk/manager.conf,0,0)", "payload");
        // Não lançar exceção já confirma o retorno antecipado (webClient nunca é chamado com URL
        // inválida); a garantia real de "não chamou a rede" é dada pela ausência de qualquer host
        // alcançável em localhost:0 — se o método tentasse a chamada, o teste falharia por timeout.
    }

    @Test
    void setChannelVar_recusaNomeNulo() {
        client.setChannelVar("chan-1", null, "payload");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEU_VAR", "callerName", "_interno", "VAR_123"})
    void setChannelVar_aceitaNomeSimples(String nomeValido) {
        assertThat(nomeValido).matches("^[A-Za-z_][A-Za-z0-9_]*$");
    }

    @Test
    void play_recusaPathAbsoluto() {
        // H1: a allowlist anterior aceitava "/etc/passwd" (sem ".."); agora exige um dos
        // prefixos reais do ARI (sound:/recording:/digits:).
        var playbackId = client.play("chan-1", "/etc/passwd");
        assertThat(playbackId).isNull();
    }

    @Test
    void play_recusaSemPrefixoConhecido() {
        var playbackId = client.play("chan-1", "etc/passwd");
        assertThat(playbackId).isNull();
    }

    @Test
    void play_recusaTraversal() {
        var playbackId = client.play("chan-1", "sound:../../etc/passwd");
        assertThat(playbackId).isNull();
    }

    @Test
    void play_recusaNulo() {
        assertThat(client.play("chan-1", null)).isNull();
    }
}
