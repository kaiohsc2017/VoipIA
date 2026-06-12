package com.asteriskia.domain.call;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class ExcelExportService {

    public byte[] exportCallRecordsToExcel(List<CallRecord> records) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Chamadas");

            // Header
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("ID");
            headerRow.createCell(1).setCellValue("Data da Chamada");
            headerRow.createCell(2).setCellValue("Duração (s)");
            headerRow.createCell(3).setCellValue("Número do Cliente");
            headerRow.createCell(4).setCellValue("Nome do Cliente");
            headerRow.createCell(5).setCellValue("Jira Issue");
            headerRow.createCell(6).setCellValue("Status Jira");

            // Data
            int rowIdx = 1;
            for (CallRecord record : records) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(record.getId());
                row.createCell(1).setCellValue(record.getCallDate() != null ? record.getCallDate().toString() : "");
                row.createCell(2).setCellValue(record.getCallDurationSecs() != null ? record.getCallDurationSecs() : 0);
                row.createCell(3).setCellValue(record.getCallerNumber() != null ? record.getCallerNumber() : "");
                row.createCell(4).setCellValue(record.getClientName() != null ? record.getClientName() : "");
                row.createCell(5).setCellValue(record.getJiraIssueKey() != null ? record.getJiraIssueKey() : "");
                row.createCell(6).setCellValue(record.getJiraIssueStatus() != null ? record.getJiraIssueStatus() : "");
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }
}
