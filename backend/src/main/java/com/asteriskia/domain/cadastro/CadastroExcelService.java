package com.asteriskia.domain.cadastro;

import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import com.asteriskia.domain.masterdata.Client;
import com.asteriskia.domain.masterdata.ClientRepository;
import com.asteriskia.domain.masterdata.Operadora;
import com.asteriskia.domain.masterdata.OperadoraRepository;
import com.asteriskia.domain.masterdata.Operation;
import com.asteriskia.domain.masterdata.OperationRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CadastroExcelService — exportação, modelo de importação e importação em
 * lote (XLSX) dos cadastros de Números 0800 e Linhas. Colunas identificadas
 * por nome de cabeçalho (não por posição) — mais tolerante a reordenação da
 * planilha pelo usuário do que o parser CSV posicional de
 * {@code MasterDataController.importNumberTests}.
 */
@Service
@RequiredArgsConstructor
public class CadastroExcelService {

    private static final int MAX_REGENERADOS = 5;

    private final OperadoraRepository operadoraRepo;
    private final ClientRepository clientRepo;
    private final OperationRepository operationRepo;
    private final BusinessUnitRepository buRepo;

    // -----------------------------------------------------------------------
    // Números 0800
    // -----------------------------------------------------------------------

    private static List<String> numero0800Headers() {
        List<String> headers = new ArrayList<>(List.of(
                "Operadora", "Número", "Cliente", "Observação", "Ativo", "BUs (separadas por vírgula)"));
        for (int i = 1; i <= MAX_REGENERADOS; i++) {
            headers.add("Regenerado " + i + " - Número");
            headers.add("Regenerado " + i + " - VDN");
            headers.add("Regenerado " + i + " - Vetor");
            headers.add("Regenerado " + i + " - Operadora");
        }
        return headers;
    }

    public byte[] templateNumeros0800() throws IOException {
        List<String> refHeaders = List.of("Operadoras cadastradas", "Clientes cadastrados", "BUs cadastradas");
        List<String> operadoraNomes = operadoraRepo.findAll().stream().map(Operadora::getNome).toList();
        List<String> clientNomes = clientRepo.findAll().stream().map(Client::getName).toList();
        List<String> buNomes = visibleBUs().stream().map(BusinessUnit::getName).toList();
        List<List<String>> refRows = referenceRows(operadoraNomes, clientNomes, buNomes);
        return buildTemplateWorkbook(numero0800Headers(), refHeaders, refRows);
    }

    public byte[] exportNumeros0800(List<Numero0800> items) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (Numero0800 n : items) {
            List<String> row = new ArrayList<>(List.of(
                    nomeOrBlank(n.getOperadora() != null ? n.getOperadora().getNome() : null),
                    blank(n.getNumero()),
                    n.getClient() != null ? blank(n.getClient().getName()) : "",
                    blank(n.getObservacao()),
                    Boolean.TRUE.equals(n.getIsActive()) ? "Sim" : "Não",
                    n.getBusinessUnits().stream().map(BusinessUnit::getName).collect(Collectors.joining(", "))));
            List<Numero0800Regenerado> regs = n.getRegenerados().stream()
                    .sorted(Comparator.comparing(Numero0800Regenerado::getOrdem))
                    .toList();
            for (int i = 0; i < MAX_REGENERADOS; i++) {
                Numero0800Regenerado r = i < regs.size() ? regs.get(i) : null;
                row.add(r != null ? blank(r.getNumeroRegenerado()) : "");
                row.add(r != null ? blank(r.getVdn()) : "");
                row.add(r != null ? blank(r.getVetor()) : "");
                row.add(r != null && r.getOperadora() != null ? blank(r.getOperadora().getNome()) : "");
            }
            rows.add(row);
        }
        return buildWorkbook("Números 0800", numero0800Headers(), rows);
    }

    public ImportResult<Numero0800> importNumeros0800(MultipartFile file) throws IOException {
        List<Numero0800> toSave = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();

        Map<String, Operadora> operadoraMap = mapByName(operadoraRepo.findAll(), Operadora::getNome);
        Map<String, Client> clientMap = mapByName(clientRepo.findAll(), Client::getName);
        Map<String, BusinessUnit> buMap = mapByName(visibleBUs(), BusinessUnit::getName);

        int lineNumber = 1;
        for (Map<String, String> cols : readRows(file)) {
            lineNumber++;
            try {
                String numero = col(cols, "Número");
                if (numero.isBlank()) throw new IllegalArgumentException("Número vazio");
                requireMaxLength(numero, 40, "Número");
                String observacao = col(cols, "Observação");
                requireMaxLength(observacao, 500, "Observação");

                Numero0800 n = new Numero0800();
                n.setOperadora(resolveOperadora(col(cols, "Operadora"), operadoraMap));
                n.setNumero(numero);
                n.setObservacao(observacao);
                n.setIsActive(parseAtivo(col(cols, "Ativo")));

                String clienteNome = col(cols, "Cliente");
                if (!clienteNome.isBlank()) {
                    Client c = clientMap.get(norm(clienteNome));
                    if (c == null) throw new IllegalArgumentException("Cliente não encontrado: '" + clienteNome + "'");
                    n.setClient(c);
                }
                n.setBusinessUnits(resolveBUs(col(cols, "BUs (separadas por vírgula)"), buMap));
                n.setRegenerados(readRegenerados(cols, operadoraMap));

                toSave.add(n);
            } catch (Exception e) {
                errors.add(new ImportError(lineNumber, e.getMessage()));
            }
        }
        return new ImportResult<>(toSave, errors);
    }

    private List<Numero0800Regenerado> readRegenerados(Map<String, String> cols, Map<String, Operadora> operadoraMap) {
        List<Numero0800Regenerado> regenerados = new ArrayList<>();
        for (int i = 1; i <= MAX_REGENERADOS; i++) {
            String numReg = col(cols, "Regenerado " + i + " - Número");
            String vdn = col(cols, "Regenerado " + i + " - VDN");
            String vetor = col(cols, "Regenerado " + i + " - Vetor");
            String opReg = col(cols, "Regenerado " + i + " - Operadora");
            if (numReg.isBlank() && vdn.isBlank() && vetor.isBlank() && opReg.isBlank()) continue;
            requireMaxLength(numReg, 40, "Regenerado " + i + " - Número");
            requireMaxLength(vdn, 40, "Regenerado " + i + " - VDN");
            requireMaxLength(vetor, 100, "Regenerado " + i + " - Vetor");

            Numero0800Regenerado r = new Numero0800Regenerado();
            r.setOrdem(regenerados.size() + 1);
            r.setNumeroRegenerado(numReg);
            r.setVdn(vdn);
            r.setVetor(vetor);
            if (!opReg.isBlank()) {
                Operadora ro = operadoraMap.get(norm(opReg));
                if (ro == null) throw new IllegalArgumentException("Operadora do regenerado " + i + " não encontrada: '" + opReg + "'");
                r.setOperadora(ro);
            }
            regenerados.add(r);
        }
        return regenerados;
    }

    // -----------------------------------------------------------------------
    // Linhas
    // -----------------------------------------------------------------------

    private static final List<String> LINHA_HEADERS = List.of(
            "Operadora", "Operação", "Chave", "IP Operadora", "IP Autoglass", "Observação",
            "Ativo", "BUs (separadas por vírgula)");

    public byte[] templateLinhas() throws IOException {
        List<String> refHeaders = List.of("Operadoras cadastradas", "Operações cadastradas", "BUs cadastradas");
        List<String> operadoraNomes = operadoraRepo.findAll().stream().map(Operadora::getNome).toList();
        List<String> operationNomes = operationRepo.findAll().stream().map(Operation::getName).toList();
        List<String> buNomes = visibleBUs().stream().map(BusinessUnit::getName).toList();
        List<List<String>> refRows = referenceRows(operadoraNomes, operationNomes, buNomes);
        return buildTemplateWorkbook(LINHA_HEADERS, refHeaders, refRows);
    }

    public byte[] exportLinhas(List<Linha> items) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (Linha l : items) {
            rows.add(List.of(
                    nomeOrBlank(l.getOperadora() != null ? l.getOperadora().getNome() : null),
                    l.getOperation() != null ? blank(l.getOperation().getName()) : "",
                    blank(l.getChave()),
                    blank(l.getIpOperadora()),
                    blank(l.getIpAutoglass()),
                    blank(l.getObservacao()),
                    Boolean.TRUE.equals(l.getIsActive()) ? "Sim" : "Não",
                    l.getBusinessUnits().stream().map(BusinessUnit::getName).collect(Collectors.joining(", "))));
        }
        return buildWorkbook("Linhas", LINHA_HEADERS, rows);
    }

    public ImportResult<Linha> importLinhas(MultipartFile file) throws IOException {
        List<Linha> toSave = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();

        Map<String, Operadora> operadoraMap = mapByName(operadoraRepo.findAll(), Operadora::getNome);
        Map<String, Operation> operationMap = mapByName(operationRepo.findAll(), Operation::getName);
        Map<String, BusinessUnit> buMap = mapByName(visibleBUs(), BusinessUnit::getName);

        int lineNumber = 1;
        for (Map<String, String> cols : readRows(file)) {
            lineNumber++;
            try {
                String chave = col(cols, "Chave");
                String ipOperadora = col(cols, "IP Operadora");
                String ipAutoglass = col(cols, "IP Autoglass");
                String observacao = col(cols, "Observação");
                requireMaxLength(chave, 200, "Chave");
                requireMaxLength(ipOperadora, 64, "IP Operadora");
                requireMaxLength(ipAutoglass, 64, "IP Autoglass");
                requireMaxLength(observacao, 500, "Observação");

                Linha l = new Linha();
                l.setOperadora(resolveOperadora(col(cols, "Operadora"), operadoraMap));
                l.setChave(chave);
                l.setIpOperadora(ipOperadora);
                l.setIpAutoglass(ipAutoglass);
                l.setObservacao(observacao);
                l.setIsActive(parseAtivo(col(cols, "Ativo")));

                String operacaoNome = col(cols, "Operação");
                if (!operacaoNome.isBlank()) {
                    Operation op = operationMap.get(norm(operacaoNome));
                    if (op == null) throw new IllegalArgumentException("Operação não encontrada: '" + operacaoNome + "'");
                    l.setOperation(op);
                }
                l.setBusinessUnits(resolveBUs(col(cols, "BUs (separadas por vírgula)"), buMap));

                toSave.add(l);
            } catch (Exception e) {
                errors.add(new ImportError(lineNumber, e.getMessage()));
            }
        }
        return new ImportResult<>(toSave, errors);
    }

    // -----------------------------------------------------------------------
    // Helpers compartilhados
    // -----------------------------------------------------------------------

    /**
     * BUs visíveis ao usuário logado: todas, se ele não for restrito por BU
     * (ADMIN ou sem BU vinculada); só as próprias, se restrito. Sem isso, um
     * usuário restrito poderia usar a coluna "BUs" da planilha (referência ou
     * importação) para descobrir nomes de BUs de terceiros ou vincular
     * registros a BUs fora do seu escopo em lote.
     */
    private List<BusinessUnit> visibleBUs() {
        List<BusinessUnit> all = buRepo.findAll();
        if (!BusinessUnitContext.isRestricted()) return all;
        var allowed = BusinessUnitContext.currentBusinessUnitIds();
        return all.stream().filter(bu -> allowed.contains(bu.getId())).toList();
    }

    private Operadora resolveOperadora(String nome, Map<String, Operadora> operadoraMap) {
        if (nome.isBlank()) throw new IllegalArgumentException("Operadora vazia");
        Operadora o = operadoraMap.get(norm(nome));
        if (o == null) throw new IllegalArgumentException("Operadora não encontrada: '" + nome + "'");
        return o;
    }

    private java.util.Set<BusinessUnit> resolveBUs(String buNames, Map<String, BusinessUnit> buMap) {
        if (buNames == null || buNames.isBlank()) return new java.util.HashSet<>();
        java.util.Set<BusinessUnit> result = new java.util.HashSet<>();
        for (String nome : buNames.split(",")) {
            String trimmed = nome.trim();
            if (trimmed.isEmpty()) continue;
            BusinessUnit bu = buMap.get(norm(trimmed));
            if (bu == null) throw new IllegalArgumentException("Unidade de Negócio não encontrada: '" + trimmed + "'");
            result.add(bu);
        }
        return result;
    }

    private static boolean parseAtivo(String value) {
        String v = norm(value);
        return !v.equals("nao") && !v.equals("false") && !v.equals("0") && !v.equals("inativo");
    }

    private static <T> Map<String, T> mapByName(List<T> items, Function<T, String> nameOf) {
        return items.stream().collect(Collectors.toMap(i -> norm(nameOf.apply(i)), Function.identity(), (a, b) -> a));
    }

    private static String col(Map<String, String> row, String header) {
        return row.getOrDefault(norm(header), "");
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    private static String nomeOrBlank(String s) {
        return s == null ? "" : s;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("\\s+", " ");
    }

    /** Teto de linhas por importação — a transação de classe mantém uma conexão de banco aberta durante todo o processamento. */
    private static final int MAX_IMPORT_ROWS = 5000;

    /** Lança erro se o valor exceder o tamanho da coluna no banco — vira erro de linha em vez de derrubar o lote inteiro no saveAll(). */
    private static void requireMaxLength(String value, int max, String campo) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(campo + " excede o tamanho máximo de " + max + " caracteres.");
        }
    }

    /** Lê a primeira aba da planilha (XLS/XLSX) em linhas indexadas pelo cabeçalho normalizado. */
    private List<Map<String, String>> readRows(MultipartFile file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rows;

            if (sheet.getLastRowNum() > MAX_IMPORT_ROWS) {
                throw new IllegalArgumentException(
                        "Planilha excede o limite de " + MAX_IMPORT_ROWS + " linhas por importação.");
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
    private byte[] buildWorkbook(String sheetName, List<String> headers, List<List<String>> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(wb, sheetName, headers, rows);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Constrói o XLSX de modelo de importação: aba "Modelo" em branco (só o
     * cabeçalho, para o usuário preencher) + aba "Valores de Referência" com
     * os nomes já cadastrados dos campos resolvidos por nome na importação —
     * mesmo padrão de referência usado no modelo de testes de conectividade
     * (ModuloConectividade.tsx).
     */
    private byte[] buildTemplateWorkbook(List<String> headers, List<String> refHeaders, List<List<String>> refRows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(wb, "Modelo", headers, List.of());
            writeSheet(wb, "Valores de Referência", refHeaders, refRows);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Monta as linhas da aba de referência a partir de N colunas de nomes de tamanhos possivelmente diferentes. */
    @SafeVarargs
    private static List<List<String>> referenceRows(List<String>... columns) {
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

    private void writeSheet(Workbook wb, String sheetName, List<String> headers, List<List<String>> rows) {
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

    // -----------------------------------------------------------------------
    // DTOs de resultado
    // -----------------------------------------------------------------------

    public record ImportError(int linha, String erro) {}

    public record ImportResult<T>(List<T> toSave, List<ImportError> errors) {}
}
