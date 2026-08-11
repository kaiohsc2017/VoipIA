package com.asteriskia.domain.cadastro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

/**
 * ExcelSheetUtil — leitura/escrita de planilhas XLS/XLSX indexadas por cabeçalho, extraído de
 * CadastroExcelService (fase 6 da refatoração). Sem conhecimento de domínio (Números 0800/Linhas) —
 * puramente mecânico, reutilizável por qualquer importação/exportação baseada em planilha.
 */
public final class ExcelSheetUtil {

    private ExcelSheetUtil() {}

    /**
     * Teto de linhas por importação — a transação de classe do chamador mantém uma conexão de banco
     * aberta durante todo o processamento.
     */
    private static final int MAX_IMPORT_ROWS = 5000;

    /** Lê a primeira aba da planilha (XLS/XLSX) em linhas indexadas pelo cabeçalho normalizado. */
    public static List<Map<String, String>> readRows(MultipartFile file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rows;

            if (sheet.getLastRowNum() > MAX_IMPORT_ROWS) {
                throw new IllegalArgumentException(
                        "Planilha excede o limite de "
                                + MAX_IMPORT_ROWS
                                + " linhas por importação.");
            }

            Map<Integer, String> colHeaders = new HashMap<>();
            for (Cell cell : headerRow) {
                colHeaders.put(cell.getColumnIndex(), norm(cellText(cell)));
            }

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowBlank(row)) continue;
                Map<String, String> map = new HashMap<>();
                for (var entry : colHeaders.entrySet()) {
                    map.put(entry.getValue(), cellText(row.getCell(entry.getKey())));
                }
                rows.add(map);
            }
        }
        return rows;
    }

    private static boolean isRowBlank(Row row) {
        for (Cell cell : row) {
            if (!cellText(cell).isBlank()) return false;
        }
        return true;
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    /** Constrói um XLSX de uma aba só, com cabeçalho em negrito e as linhas de dados informadas. */
    public static byte[] buildWorkbook(
            String sheetName, List<String> headers, List<List<String>> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(wb, sheetName, headers, rows);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Constrói o XLSX de modelo de importação: aba "Modelo" em branco (só o cabeçalho, para o
     * usuário preencher) + aba "Valores de Referência" com os nomes já cadastrados dos campos
     * resolvidos por nome na importação — mesmo padrão de referência usado no modelo de testes de
     * conectividade (ModuloConectividade.tsx).
     */
    public static byte[] buildTemplateWorkbook(
            List<String> headers, List<String> refHeaders, List<List<String>> refRows)
            throws IOException {
        try (Workbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(wb, "Modelo", headers, List.of());
            writeSheet(wb, "Valores de Referência", refHeaders, refRows);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Monta as linhas da aba de referência a partir de N colunas de nomes de tamanhos possivelmente
     * diferentes.
     */
    @SafeVarargs
    public static List<List<String>> referenceRows(List<String>... columns) {
        int maxRows = 0;
        for (List<String> col : columns) maxRows = Math.max(maxRows, col.size());
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < maxRows; i++) {
            List<String> row = new ArrayList<>();
            for (List<String> col : columns) row.add(i < col.size() ? col.get(i) : "");
            rows.add(row);
        }
        return rows;
    }

    private static void writeSheet(
            Workbook wb, String sheetName, List<String> headers, List<List<String>> rows) {
        Sheet sheet = wb.createSheet(sheetName);

        var headerFont = wb.createFont();
        headerFont.setBold(true);
        var headerStyle = wb.createCellStyle();
        headerStyle.setFont(headerFont);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, 22 * 256);
        }

        int rowIdx = 1;
        for (List<String> rowData : rows) {
            Row row = sheet.createRow(rowIdx++);
            for (int i = 0; i < rowData.size(); i++) {
                row.createCell(i).setCellValue(rowData.get(i));
            }
        }
    }

    /** Lê o valor de uma coluna pelo nome do cabeçalho (normalizado), ou "" se ausente. */
    public static String col(Map<String, String> row, String header) {
        return row.getOrDefault(norm(header), "");
    }

    /** Normaliza texto para comparação tolerante a acentos/maiúsculas/espaços. */
    public static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("\\s+", " ");
    }
}
