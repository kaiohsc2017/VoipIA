package com.asteriskia.domain.callcenter.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AmiEventParserTest — parsing de um bloco de evento AMI para mapa chave/valor. */
class AmiEventParserTest {

    @Test
    @DisplayName("parse extrai todos os campos de um bloco AgentConnect")
    void parse_agentConnectBlock_extractsFields() {
        var block =
                "Event: AgentConnect\n"
                        + "Queue: 5001\n"
                        + "Uniqueid: 1700000000.123\n"
                        + "Member: PJSIP/4001\n"
                        + "MemberName: Agente Teste\n";

        var fields = AmiEventParser.parse(block);

        assertThat(fields)
                .containsEntry("Event", "AgentConnect")
                .containsEntry("Queue", "5001")
                .containsEntry("Uniqueid", "1700000000.123")
                .containsEntry("Member", "PJSIP/4001")
                .containsEntry("MemberName", "Agente Teste");
    }

    @Test
    @DisplayName("parse ignora linhas em branco e sem separador")
    void parse_blankAndMalformedLines_ignored() {
        var block = "Event: Hangup\n\nsem separador aqui\nUniqueid: 1700000000.456\n";

        var fields = AmiEventParser.parse(block);

        assertThat(fields).hasSize(2).containsEntry("Event", "Hangup").containsEntry(
                "Uniqueid", "1700000000.456");
    }

    @Test
    @DisplayName("parse de bloco vazio retorna mapa vazio")
    void parse_emptyBlock_returnsEmptyMap() {
        assertThat(AmiEventParser.parse("")).isEmpty();
    }

    @Test
    @DisplayName("parse preserva valor com ':' extra (ex: horário)")
    void parse_valueWithColon_keepsFullValue() {
        var fields = AmiEventParser.parse("SomeField: 10:30:00\n");

        assertThat(fields).containsEntry("SomeField", "10:30:00");
    }
}
