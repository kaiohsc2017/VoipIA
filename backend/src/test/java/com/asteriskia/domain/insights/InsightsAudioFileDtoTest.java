package com.asteriskia.domain.insights;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cobre a decisão 9 do plano insights-chamadas-campos-xml: ANI exibido troca
 * pra DNIS bruto em chamadas outbound (efetuadas), sem alterar os campos
 * brutos persistidos. Também cobre o gating ADMIN do grupo C no detalhe. */
class InsightsAudioFileDtoTest {

    private CallAudioFile audioFile(String direction, String ani, String dnis) {
        return CallAudioFile.builder()
                .id(1L)
                .callRef("VER-1")
                .direction(direction)
                .ani(ani)
                .dnis(dnis)
                .codec("G729A")
                .switchCallId("SW-123")
                .build();
    }

    @Test
    @DisplayName("resolveDisplayAni: outbound retorna o dnis bruto")
    void resolveDisplayAni_outbound_returnsDnis() {
        CallAudioFile audio = audioFile("outbound", "4021", "1197334410");
        assertThat(InsightsAudioFileDto.resolveDisplayAni(audio)).isEqualTo("1197334410");
    }

    @Test
    @DisplayName("resolveDisplayAni: inbound retorna o ani bruto")
    void resolveDisplayAni_inbound_returnsAni() {
        CallAudioFile audio = audioFile("inbound", "16991379262", "994850");
        assertThat(InsightsAudioFileDto.resolveDisplayAni(audio)).isEqualTo("16991379262");
    }

    @Test
    @DisplayName("resolveDisplayAni: direção desconhecida (null) trata como inbound (fallback seguro)")
    void resolveDisplayAni_unknownDirection_returnsAni() {
        CallAudioFile audio = audioFile(null, "16991379262", "994850");
        assertThat(InsightsAudioFileDto.resolveDisplayAni(audio)).isEqualTo("16991379262");
    }

    @Test
    @DisplayName("dado bruto de ani/dnis nunca é alterado, só a exibição")
    void rawAniDnis_neverMutated() {
        CallAudioFile audio = audioFile("outbound", "4021", "1197334410");
        InsightsAudioFileDto.from(audio, true);
        assertThat(audio.getAni()).isEqualTo("4021");
        assertThat(audio.getDnis()).isEqualTo("1197334410");
    }

    @Test
    @DisplayName("grupo C ausente do DTO quando isAdmin=false")
    void groupC_nulledForNonAdmin() {
        CallAudioFile audio = audioFile("inbound", "16991379262", "994850");
        InsightsAudioFileDto dto = InsightsAudioFileDto.from(audio, false);
        assertThat(dto.codec()).isNull();
        assertThat(dto.switchCallId()).isNull();
    }

    @Test
    @DisplayName("grupo C presente no DTO quando isAdmin=true")
    void groupC_presentForAdmin() {
        CallAudioFile audio = audioFile("inbound", "16991379262", "994850");
        InsightsAudioFileDto dto = InsightsAudioFileDto.from(audio, true);
        assertThat(dto.codec()).isEqualTo("G729A");
        assertThat(dto.switchCallId()).isEqualTo("SW-123");
    }
}
