package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AniNormalizerTest {

    @Test
    void normalize_null_returnsNull() {
        assertThat(AniNormalizer.normalize(null)).isNull();
    }

    @Test
    void normalize_semDigitos_returnsNull() {
        assertThat(AniNormalizer.normalize("---")).isNull();
    }

    @Test
    void normalize_comCodigoDoPais_removeOs55() {
        assertThat(AniNormalizer.normalize("+55 11 91234-5678")).isEqualTo("11912345678");
    }

    @Test
    void normalize_celularSemO9Digito_insereO9() {
        assertThat(AniNormalizer.normalize("11 8123-4567")).isEqualTo("11981234567");
    }

    @Test
    void normalize_celularComO9Digito_mantemInalterado() {
        assertThat(AniNormalizer.normalize("(11) 91234-5678")).isEqualTo("11912345678");
    }

    @Test
    void normalize_fixoSemPrefixoDeCelular_mantemDezDigitos() {
        // DDD + fixo (3º dígito < 6) não é celular — não insere o 9º dígito.
        assertThat(AniNormalizer.normalize("11 3123-4567")).isEqualTo("1131234567");
    }

    @Test
    void normalize_ramalInterno_mantemInalterado() {
        assertThat(AniNormalizer.normalize("1001")).isEqualTo("1001");
    }

    @Test
    void normalize_duasVariacoesDoMesmoCelular_convergemParaAMesmaChave() {
        String comNove = AniNormalizer.normalize("+55 (11) 98123-4567");
        String semNove = AniNormalizer.normalize("11 8123-4567");
        assertThat(comNove).isEqualTo(semNove).isEqualTo("11981234567");
    }
}
