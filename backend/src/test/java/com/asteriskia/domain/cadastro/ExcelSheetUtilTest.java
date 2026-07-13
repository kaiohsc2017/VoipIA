package com.asteriskia.domain.cadastro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * ExcelSheetUtilTest — teste de caracterização (fase 6 da refatoração). Trava o comportamento da
 * maquinaria de planilha extraída de CadastroExcelService: ciclo completo escrita → leitura,
 * normalização de cabeçalho e montagem da aba de referência.
 */
class ExcelSheetUtilTest {

    private static MultipartFile toMultipartFile(byte[] bytes) {
        return new MockMultipartFile("file", "planilha.xlsx", "application/xlsx", bytes);
    }

    @Test
    void buildWorkbook_eReadRows_devemFazerRoundTripDosDados() throws IOException {
        List<String> headers = List.of("Nome", "Idade");
        List<List<String>> rows = List.of(List.of("Ana", "30"), List.of("Bruno", "40"));

        byte[] xlsx = ExcelSheetUtil.buildWorkbook("Planilha", headers, rows);
        List<Map<String, String>> result = ExcelSheetUtil.readRows(toMultipartFile(xlsx));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("nome", "Ana").containsEntry("idade", "30");
        assertThat(result.get(1)).containsEntry("nome", "Bruno").containsEntry("idade", "40");
    }

    @Test
    void readRows_linhaTotalmenteEmBranco_deveSerIgnorada() throws IOException {
        List<String> headers = List.of("Nome");
        byte[] xlsx = ExcelSheetUtil.buildWorkbook("Planilha", headers, List.of());

        // Adiciona uma linha em branco manualmente, simulando o usuário deixando uma linha vazia.
        try (Workbook wb = WorkbookFactory.create(new java.io.ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            sheet.createRow(1).createCell(0).setCellValue("");
            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            xlsx = out.toByteArray();
        }

        assertThat(ExcelSheetUtil.readRows(toMultipartFile(xlsx))).isEmpty();
    }

    @Test
    void buildTemplateWorkbook_deveCriarAbaModeloEAbaDeReferencia() throws IOException {
        byte[] xlsx =
                ExcelSheetUtil.buildTemplateWorkbook(
                        List.of("Coluna A"),
                        List.of("Ref A"),
                        ExcelSheetUtil.referenceRows(List.of("valor1", "valor2")));

        try (Workbook wb = WorkbookFactory.create(new java.io.ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("Modelo");
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("Valores de Referência");
            assertThat(wb.getSheetAt(1).getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("valor1");
        }
    }

    @Test
    void referenceRows_colunasDeTamanhosDiferentes_devePreencherComVazio() {
        List<List<String>> rows =
                ExcelSheetUtil.referenceRows(List.of("a", "b", "c"), List.of("x"));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("a", "x");
        assertThat(rows.get(1)).containsExactly("b", "");
        assertThat(rows.get(2)).containsExactly("c", "");
    }

    @Test
    void col_deveIgnorarAcentosCaixaEEspacosNoNomeDoCabecalho() {
        Map<String, String> row = Map.of("bus (separadas por virgula)", "Matriz");

        assertThat(ExcelSheetUtil.col(row, "BUs (separadas por vírgula)")).isEqualTo("Matriz");
    }

    @Test
    void col_cabecalhoAusente_deveRetornarVazio() {
        assertThat(ExcelSheetUtil.col(Map.of(), "Qualquer")).isEqualTo("");
    }
}
