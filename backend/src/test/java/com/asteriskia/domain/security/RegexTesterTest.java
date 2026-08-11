package com.asteriskia.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * RegexTesterTest — teste de caracterização (fase 7 da refatoração). Trava o comportamento do teste
 * de regex com timeout extraído de SecurityController.
 */
class RegexTesterTest {

    @Test
    void runWithTimeout_regexSimples_devolveApenasLinhasQueCasam() throws TimeoutException {
        List<String> log = List.of("ERROR algo quebrou", "tudo normal", "ERROR outra falha");
        Pattern p = Pattern.compile("ERROR");

        List<String> matches = RegexTester.runWithTimeout(p, log);

        assertThat(matches).containsExactly("ERROR algo quebrou", "ERROR outra falha");
    }

    @Test
    void runWithTimeout_semNenhumaLinhaCasando_devolveVazio() throws TimeoutException {
        List<String> log = List.of("tudo normal por aqui");
        Pattern p = Pattern.compile("ERROR");

        assertThat(RegexTester.runWithTimeout(p, log)).isEmpty();
    }

    @Test
    void runWithTimeout_maisDeVinteCasamentos_deveLimitarA20() throws TimeoutException {
        List<String> log =
                java.util.stream.IntStream.range(0, 50).mapToObj(i -> "ERROR " + i).toList();
        Pattern p = Pattern.compile("ERROR");

        assertThat(RegexTester.runWithTimeout(p, log)).hasSize(20);
    }
}
