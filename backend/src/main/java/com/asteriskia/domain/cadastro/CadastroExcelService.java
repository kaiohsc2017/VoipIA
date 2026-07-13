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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * CadastroExcelService — exportação, modelo de importação e importação em lote (XLSX) dos cadastros
 * de Números 0800 e Linhas. Colunas identificadas por nome de cabeçalho (não por posição) — mais
 * tolerante a reordenação da planilha pelo usuário do que o parser CSV posicional de {@code
 * MasterDataController.importNumberTests}. A leitura/escrita da planilha em si (sem conhecimento de
 * domínio) vive em ExcelSheetUtil (fase 6 da refatoração).
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
        List<String> headers =
                new ArrayList<>(
                        List.of(
                                "Operadora",
                                "Número",
                                "Cliente",
                                "Observação",
                                "Ativo",
                                "BUs (separadas por vírgula)"));
        for (int i = 1; i <= MAX_REGENERADOS; i++) {
            headers.add("Regenerado " + i + " - Número");
            headers.add("Regenerado " + i + " - VDN");
            headers.add("Regenerado " + i + " - Vetor");
            headers.add("Regenerado " + i + " - Operadora");
        }
        return headers;
    }

    public byte[] templateNumeros0800() throws IOException {
        List<String> refHeaders =
                List.of("Operadoras cadastradas", "Clientes cadastrados", "BUs cadastradas");
        List<String> operadoraNomes =
                operadoraRepo.findAll().stream().map(Operadora::getNome).toList();
        List<String> clientNomes = clientRepo.findAll().stream().map(Client::getName).toList();
        List<String> buNomes = visibleBUs().stream().map(BusinessUnit::getName).toList();
        List<List<String>> refRows =
                ExcelSheetUtil.referenceRows(operadoraNomes, clientNomes, buNomes);
        return ExcelSheetUtil.buildTemplateWorkbook(numero0800Headers(), refHeaders, refRows);
    }

    public byte[] exportNumeros0800(List<Numero0800> items) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (Numero0800 n : items) {
            List<String> row =
                    new ArrayList<>(
                            List.of(
                                    nomeOrBlank(
                                            n.getOperadora() != null
                                                    ? n.getOperadora().getNome()
                                                    : null),
                                    blank(n.getNumero()),
                                    n.getClient() != null ? blank(n.getClient().getName()) : "",
                                    blank(n.getObservacao()),
                                    Boolean.TRUE.equals(n.getIsActive()) ? "Sim" : "Não",
                                    n.getBusinessUnits().stream()
                                            .map(BusinessUnit::getName)
                                            .collect(Collectors.joining(", "))));
            List<Numero0800Regenerado> regs =
                    n.getRegenerados().stream()
                            .sorted(Comparator.comparing(Numero0800Regenerado::getOrdem))
                            .toList();
            for (int i = 0; i < MAX_REGENERADOS; i++) {
                Numero0800Regenerado r = i < regs.size() ? regs.get(i) : null;
                row.add(r != null ? blank(r.getNumeroRegenerado()) : "");
                row.add(r != null ? blank(r.getVdn()) : "");
                row.add(r != null ? blank(r.getVetor()) : "");
                row.add(
                        r != null && r.getOperadora() != null
                                ? blank(r.getOperadora().getNome())
                                : "");
            }
            rows.add(row);
        }
        return ExcelSheetUtil.buildWorkbook("Números 0800", numero0800Headers(), rows);
    }

    public ImportResult<Numero0800> importNumeros0800(MultipartFile file) throws IOException {
        List<Numero0800> toSave = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();

        Map<String, Operadora> operadoraMap =
                mapByName(operadoraRepo.findAll(), Operadora::getNome);
        Map<String, Client> clientMap = mapByName(clientRepo.findAll(), Client::getName);
        Map<String, BusinessUnit> buMap = mapByName(visibleBUs(), BusinessUnit::getName);

        int lineNumber = 1;
        for (Map<String, String> cols : ExcelSheetUtil.readRows(file)) {
            lineNumber++;
            try {
                String numero = ExcelSheetUtil.col(cols, "Número");
                if (numero.isBlank()) throw new IllegalArgumentException("Número vazio");
                requireMaxLength(numero, 40, "Número");
                String observacao = ExcelSheetUtil.col(cols, "Observação");
                requireMaxLength(observacao, 500, "Observação");

                Numero0800 n = new Numero0800();
                n.setOperadora(
                        resolveOperadora(ExcelSheetUtil.col(cols, "Operadora"), operadoraMap));
                n.setNumero(numero);
                n.setObservacao(observacao);
                n.setIsActive(parseAtivo(ExcelSheetUtil.col(cols, "Ativo")));

                String clienteNome = ExcelSheetUtil.col(cols, "Cliente");
                if (!clienteNome.isBlank()) {
                    Client c = clientMap.get(ExcelSheetUtil.norm(clienteNome));
                    if (c == null)
                        throw new IllegalArgumentException(
                                "Cliente não encontrado: '" + clienteNome + "'");
                    n.setClient(c);
                }
                n.setBusinessUnits(
                        resolveBUs(ExcelSheetUtil.col(cols, "BUs (separadas por vírgula)"), buMap));
                n.setRegenerados(readRegenerados(cols, operadoraMap));

                toSave.add(n);
            } catch (Exception e) {
                errors.add(new ImportError(lineNumber, e.getMessage()));
            }
        }
        return new ImportResult<>(toSave, errors);
    }

    private List<Numero0800Regenerado> readRegenerados(
            Map<String, String> cols, Map<String, Operadora> operadoraMap) {
        List<Numero0800Regenerado> regenerados = new ArrayList<>();
        for (int i = 1; i <= MAX_REGENERADOS; i++) {
            String numReg = ExcelSheetUtil.col(cols, "Regenerado " + i + " - Número");
            String vdn = ExcelSheetUtil.col(cols, "Regenerado " + i + " - VDN");
            String vetor = ExcelSheetUtil.col(cols, "Regenerado " + i + " - Vetor");
            String opReg = ExcelSheetUtil.col(cols, "Regenerado " + i + " - Operadora");
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
                Operadora ro = operadoraMap.get(ExcelSheetUtil.norm(opReg));
                if (ro == null)
                    throw new IllegalArgumentException(
                            "Operadora do regenerado " + i + " não encontrada: '" + opReg + "'");
                r.setOperadora(ro);
            }
            regenerados.add(r);
        }
        return regenerados;
    }

    // -----------------------------------------------------------------------
    // Linhas
    // -----------------------------------------------------------------------

    private static final List<String> LINHA_HEADERS =
            List.of(
                    "Operadora",
                    "Operação",
                    "Chave",
                    "IP Operadora",
                    "IP Autoglass",
                    "Observação",
                    "Ativo",
                    "BUs (separadas por vírgula)");

    public byte[] templateLinhas() throws IOException {
        List<String> refHeaders =
                List.of("Operadoras cadastradas", "Operações cadastradas", "BUs cadastradas");
        List<String> operadoraNomes =
                operadoraRepo.findAll().stream().map(Operadora::getNome).toList();
        List<String> operationNomes =
                operationRepo.findAll().stream().map(Operation::getName).toList();
        List<String> buNomes = visibleBUs().stream().map(BusinessUnit::getName).toList();
        List<List<String>> refRows =
                ExcelSheetUtil.referenceRows(operadoraNomes, operationNomes, buNomes);
        return ExcelSheetUtil.buildTemplateWorkbook(LINHA_HEADERS, refHeaders, refRows);
    }

    public byte[] exportLinhas(List<Linha> items) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (Linha l : items) {
            rows.add(
                    List.of(
                            nomeOrBlank(
                                    l.getOperadora() != null ? l.getOperadora().getNome() : null),
                            l.getOperation() != null ? blank(l.getOperation().getName()) : "",
                            blank(l.getChave()),
                            blank(l.getIpOperadora()),
                            blank(l.getIpAutoglass()),
                            blank(l.getObservacao()),
                            Boolean.TRUE.equals(l.getIsActive()) ? "Sim" : "Não",
                            l.getBusinessUnits().stream()
                                    .map(BusinessUnit::getName)
                                    .collect(Collectors.joining(", "))));
        }
        return ExcelSheetUtil.buildWorkbook("Linhas", LINHA_HEADERS, rows);
    }

    public ImportResult<Linha> importLinhas(MultipartFile file) throws IOException {
        List<Linha> toSave = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();

        Map<String, Operadora> operadoraMap =
                mapByName(operadoraRepo.findAll(), Operadora::getNome);
        Map<String, Operation> operationMap =
                mapByName(operationRepo.findAll(), Operation::getName);
        Map<String, BusinessUnit> buMap = mapByName(visibleBUs(), BusinessUnit::getName);

        int lineNumber = 1;
        for (Map<String, String> cols : ExcelSheetUtil.readRows(file)) {
            lineNumber++;
            try {
                String chave = ExcelSheetUtil.col(cols, "Chave");
                String ipOperadora = ExcelSheetUtil.col(cols, "IP Operadora");
                String ipAutoglass = ExcelSheetUtil.col(cols, "IP Autoglass");
                String observacao = ExcelSheetUtil.col(cols, "Observação");
                requireMaxLength(chave, 200, "Chave");
                requireMaxLength(ipOperadora, 64, "IP Operadora");
                requireMaxLength(ipAutoglass, 64, "IP Autoglass");
                requireMaxLength(observacao, 500, "Observação");

                Linha l = new Linha();
                l.setOperadora(
                        resolveOperadora(ExcelSheetUtil.col(cols, "Operadora"), operadoraMap));
                l.setChave(chave);
                l.setIpOperadora(ipOperadora);
                l.setIpAutoglass(ipAutoglass);
                l.setObservacao(observacao);
                l.setIsActive(parseAtivo(ExcelSheetUtil.col(cols, "Ativo")));

                String operacaoNome = ExcelSheetUtil.col(cols, "Operação");
                if (!operacaoNome.isBlank()) {
                    Operation op = operationMap.get(ExcelSheetUtil.norm(operacaoNome));
                    if (op == null)
                        throw new IllegalArgumentException(
                                "Operação não encontrada: '" + operacaoNome + "'");
                    l.setOperation(op);
                }
                l.setBusinessUnits(
                        resolveBUs(ExcelSheetUtil.col(cols, "BUs (separadas por vírgula)"), buMap));

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
     * BUs visíveis ao usuário logado: todas, se ele não for restrito por BU (ADMIN ou sem BU
     * vinculada); só as próprias, se restrito. Sem isso, um usuário restrito poderia usar a coluna
     * "BUs" da planilha (referência ou importação) para descobrir nomes de BUs de terceiros ou
     * vincular registros a BUs fora do seu escopo em lote.
     */
    private List<BusinessUnit> visibleBUs() {
        List<BusinessUnit> all = buRepo.findAll();
        if (!BusinessUnitContext.isRestricted()) return all;
        var allowed = BusinessUnitContext.currentBusinessUnitIds();
        return all.stream().filter(bu -> allowed.contains(bu.getId())).toList();
    }

    private Operadora resolveOperadora(String nome, Map<String, Operadora> operadoraMap) {
        if (nome.isBlank()) throw new IllegalArgumentException("Operadora vazia");
        Operadora o = operadoraMap.get(ExcelSheetUtil.norm(nome));
        if (o == null)
            throw new IllegalArgumentException("Operadora não encontrada: '" + nome + "'");
        return o;
    }

    private java.util.Set<BusinessUnit> resolveBUs(
            String buNames, Map<String, BusinessUnit> buMap) {
        if (buNames == null || buNames.isBlank()) return new java.util.HashSet<>();
        java.util.Set<BusinessUnit> result = new java.util.HashSet<>();
        for (String nome : buNames.split(",")) {
            String trimmed = nome.trim();
            if (trimmed.isEmpty()) continue;
            BusinessUnit bu = buMap.get(ExcelSheetUtil.norm(trimmed));
            if (bu == null)
                throw new IllegalArgumentException(
                        "Unidade de Negócio não encontrada: '" + trimmed + "'");
            result.add(bu);
        }
        return result;
    }

    private static boolean parseAtivo(String value) {
        String v = ExcelSheetUtil.norm(value);
        return !v.equals("nao") && !v.equals("false") && !v.equals("0") && !v.equals("inativo");
    }

    private static <T> Map<String, T> mapByName(List<T> items, Function<T, String> nameOf) {
        return items.stream()
                .collect(
                        Collectors.toMap(
                                i -> ExcelSheetUtil.norm(nameOf.apply(i)),
                                Function.identity(),
                                (a, b) -> a));
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    private static String nomeOrBlank(String s) {
        return s == null ? "" : s;
    }

    /**
     * Lança erro se o valor exceder o tamanho da coluna no banco — vira erro de linha em vez de
     * derrubar o lote inteiro no saveAll().
     */
    private static void requireMaxLength(String value, int max, String campo) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(
                    campo + " excede o tamanho máximo de " + max + " caracteres.");
        }
    }

    // -----------------------------------------------------------------------
    // DTOs de resultado
    // -----------------------------------------------------------------------

    public record ImportError(int linha, String erro) {}

    public record ImportResult<T>(List<T> toSave, List<ImportError> errors) {}
}
