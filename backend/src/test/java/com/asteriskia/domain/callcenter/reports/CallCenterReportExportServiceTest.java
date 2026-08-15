package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre a proteção contra injeção de fórmula no Excel (mesma classe de achado já corrigida em
 * {@code ReportCsvBuilder}, sub-fase 9c.5) e o teto de 50 mil linhas nos dois formatos.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterReportExportServiceTest {

    @Mock
    private CallCenterDetailReportService detailReportService;

    private CallCenterReportExportService service;

    @Test
    @DisplayName("Excel prefixa campo iniciado com = com apóstrofo, sem quebrar em outras colunas")
    void exportCallsExcel_escapesFormulaInjection() throws Exception {
        service = new CallCenterReportExportService(detailReportService);
        CallReportRow row = new CallReportRow(
                1L, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), "INBOUND",
                "=cmd|'/c calc'!A1", "Suporte", "Agente 1", 10L, BigDecimal.TEN,
                null, null, null, null, null, null, null, Map.of());
        when(detailReportService.searchCalls(any(), any(), any())).thenReturn(new PageImpl<>(List.of(row)));

        byte[] excel = service.exportCallsExcel(new CallReportFilter(
                LocalDateTime.now(), LocalDateTime.now(), null, null, null, null, null, null, null, null, null), null);

        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(excel))) {
            Sheet sheet = workbook.getSheetAt(0);
            String aniCell = sheet.getRow(1).getCell(2).getStringCellValue();
            assertThat(aniCell).startsWith("'=");
        }
    }

    @Test
    @DisplayName("exportação rejeita quando o total de linhas excede o teto de 50 mil")
    void export_rejectsWhenOverRowLimit() {
        service = new CallCenterReportExportService(detailReportService);
        Page<CallReportRow> hugePage = new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 1), 50_001);
        when(detailReportService.searchCalls(any(), any(), any())).thenReturn(hugePage);

        CallReportFilter filter = new CallReportFilter(
                LocalDateTime.now(), LocalDateTime.now(), null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.exportCallsExcel(filter, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("50000");
    }
}
