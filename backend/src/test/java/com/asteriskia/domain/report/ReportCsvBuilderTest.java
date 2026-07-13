package com.asteriskia.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.connectivity.TestResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ReportCsvBuilderTest — teste de caracterização (fase 13 da refatoração). Cobre a
 * montagem/escaping dos CSVs de relatório e o envelope de download HTTP extraídos de
 * ReportController.
 */
class ReportCsvBuilderTest {

    @Test
    void esc_valorComVirgula_ficaEntreAspas() {
        assertThat(ReportCsvBuilder.esc("Rua A, 123")).isEqualTo("\"Rua A, 123\"");
    }

    @Test
    void esc_valorComAspas_escapaAspasDuplicandoEEnvolveEmAspas() {
        assertThat(ReportCsvBuilder.esc("disse \"oi\"")).isEqualTo("\"disse \"\"oi\"\"\"");
    }

    @Test
    void esc_valorNulo_devolveVazio() {
        assertThat(ReportCsvBuilder.esc(null)).isEmpty();
    }

    @Test
    void esc_valorComecandoComIgual_prefixaApostrofoPrevenindoInjecaoDeFormula() {
        assertThat(ReportCsvBuilder.esc("=SOMA(A1:A2)")).isEqualTo("'=SOMA(A1:A2)");
    }

    @Test
    void esc_valorComecandoComArroba_prefixaApostrofoPrevenindoInjecaoDeFormula() {
        assertThat(ReportCsvBuilder.esc("@cmd")).isEqualTo("'@cmd");
    }

    @Test
    void esc_valorSemCaracterEspecial_devolveInalterado() {
        assertThat(ReportCsvBuilder.esc("valor normal")).isEqualTo("valor normal");
    }

    @Test
    void buildLabelValueCsv_arredondaValorDecimalParaUmaCasa() {
        String csv =
                ReportCsvBuilder.buildLabelValueCsv(
                        "Tipo,Duração Média (s)",
                        List.<Object[]>of(new Object[] {"suporte", 94.3333333333333333}));

        assertThat(csv).contains("suporte,94.3");
    }

    @Test
    void buildLabelValueCsv_valorInteiroNaoEArredondado() {
        String csv =
                ReportCsvBuilder.buildLabelValueCsv(
                        "Cliente,Chamadas", List.<Object[]>of(new Object[] {"Cliente X", 42}));

        assertThat(csv).contains("Cliente X,42");
    }

    @Test
    void buildConnectivityCsv_montaLinhaComCamposDoResultado() {
        TestResult result =
                TestResult.builder()
                        .id(1L)
                        .executedAt(LocalDateTime.of(2026, 1, 15, 10, 30, 0))
                        .status("SUCESSO")
                        .sipResponseCode(200)
                        .sipResponseReason("OK")
                        .build();

        String csv = ReportCsvBuilder.buildConnectivityCsv(List.of(result));

        assertThat(csv).contains("1,15/01/2026 10:30:00");
        assertThat(csv).contains("SUCESSO,200,OK");
    }

    @Test
    void buildConnectivityCsv_semNumberTest_naoQuebraEDeixaCamposVazios() {
        TestResult result =
                TestResult.builder()
                        .id(2L)
                        .executedAt(LocalDateTime.of(2026, 1, 15, 10, 30, 0))
                        .status("FALHA")
                        .build();

        String csv = ReportCsvBuilder.buildConnectivityCsv(List.of(result));

        assertThat(csv).contains("2,15/01/2026 10:30:00,,,,,,FALHA");
    }

    @Test
    void buildUraCsv_montaLinhaComCamposDaChamada() {
        CallRecord call =
                CallRecord.builder()
                        .id(10L)
                        .callDate(LocalDateTime.of(2026, 1, 15, 8, 0, 0))
                        .callerNumber("11999999999")
                        .clientName("Cliente Y")
                        .jiraIssueKey("SUP-1")
                        .jiraIssueStatus("Aberto")
                        .callDurationSecs(45)
                        .transcription("teste")
                        .build();

        String csv = ReportCsvBuilder.buildUraCsv(List.of(call));

        assertThat(csv)
                .contains("10,15/01/2026 08:00:00,11999999999,Cliente Y,SUP-1,Aberto,45,teste");
    }

    @Test
    void csvResponse_defineCabecalhosDeDownloadCsv() {
        var response = ReportCsvBuilder.csvResponse("a,b\n1,2\n", "relatorio.csv");

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"relatorio.csv\"");
        assertThat(response.getHeaders().getFirst("Content-Type"))
                .isEqualTo("text/csv; charset=UTF-8");
        assertThat(response.getBody()).isNotEmpty();
    }
}
