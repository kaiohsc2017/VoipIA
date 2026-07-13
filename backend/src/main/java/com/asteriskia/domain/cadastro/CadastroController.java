package com.asteriskia.domain.cadastro;

import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * CadastroController — CRUD dos cadastros "Números 0800" e "Linhas" (bloco Cadastros).
 *
 * <p>{@code @Transactional} em nível de classe: Numero0800/Linha carregam businessUnits (e
 * Numero0800 também regenerados) como coleção EAGER e são serializados diretamente pelo Jackson —
 * sem uma sessão Hibernate aberta durante a serialização, o acesso à coleção fora de transação
 * lança LazyInitializationException (spring.jpa.open-in-view=false neste projeto).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class CadastroController {

    private final Numero0800Repository numero0800Repo;
    private final LinhaRepository linhaRepo;
    private final BusinessUnitRepository buRepo;
    private final AuditService auditService;
    private final CadastroExcelService excelService;

    // -----------------------------------------------------------------------
    // Números 0800
    // -----------------------------------------------------------------------

    @GetMapping("/numeros-0800")
    public ResponseEntity<List<Numero0800>> listNumeros0800(
            @RequestParam(required = false) Boolean active) {
        List<Numero0800> result =
                active != null ? numero0800Repo.findByIsActive(active) : numero0800Repo.findAll();
        return ResponseEntity.ok(
                filterByBusinessUnitScope(
                        result,
                        n -> n.getBusinessUnits().stream().map(BusinessUnit::getId).toList()));
    }

    @PostMapping("/numeros-0800")
    public ResponseEntity<Numero0800> createNumero0800(
            @Valid @RequestBody Numero0800 numero0800, HttpServletRequest req) {
        Numero0800 saved = numero0800Repo.save(numero0800);
        auditService.log(
                req,
                "CADASTRO_CREATE",
                "Número 0800 criado: '"
                        + saved.getOperadora().getNome()
                        + " "
                        + saved.getNumero()
                        + "' (id="
                        + saved.getId()
                        + ")",
                true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/numeros-0800/{id}")
    public ResponseEntity<Numero0800> updateNumero0800(
            @PathVariable Integer id,
            @Valid @RequestBody Numero0800 numero0800,
            HttpServletRequest req) {
        numero0800.setId(id);
        Numero0800 saved = numero0800Repo.save(numero0800);
        auditService.log(
                req,
                "CADASTRO_UPDATE",
                "Número 0800 atualizado: '"
                        + saved.getOperadora().getNome()
                        + " "
                        + saved.getNumero()
                        + "' (id="
                        + id
                        + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/numeros-0800/{id}")
    public ResponseEntity<Void> deleteNumero0800(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "CADASTRO_DELETE", "Número 0800 removido (id=" + id + ")", true);
        numero0800Repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sincroniza (substitui por completo) as Unidades de Negócio vinculadas a um número 0800. Campo
     * opcional — lista vazia é válida e limpa a associação.
     */
    @PutMapping("/numeros-0800/{id}/business-units")
    public ResponseEntity<?> syncNumero0800BusinessUnits(
            @PathVariable Integer id,
            @RequestBody List<Integer> businessUnitIds,
            HttpServletRequest req) {
        var numero0800Opt = numero0800Repo.findById(id);
        if (numero0800Opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var resolved = resolveBusinessUnits(businessUnitIds);
        if (resolved.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Um ou mais IDs de Unidade de Negócio informados não existem."));
        }
        Numero0800 numero0800 = numero0800Opt.get();
        numero0800.setBusinessUnits(resolved.get());
        Numero0800 saved = numero0800Repo.save(numero0800);
        auditService.log(
                req,
                "CADASTRO_UPDATE",
                "BUs do número 0800 '"
                        + saved.getOperadora().getNome()
                        + " "
                        + saved.getNumero()
                        + "' atualizadas (id="
                        + id
                        + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    /** Planilha XLSX com os números 0800 cadastrados, respeitando o escopo de BU do usuário. */
    @GetMapping("/numeros-0800/export")
    public ResponseEntity<byte[]> exportNumeros0800() throws IOException {
        var items =
                filterByBusinessUnitScope(
                        numero0800Repo.findAll(),
                        n -> n.getBusinessUnits().stream().map(BusinessUnit::getId).toList());
        return xlsxResponse(excelService.exportNumeros0800(items), "numeros-0800.xlsx");
    }

    /**
     * Modelo em branco para preenchimento e posterior importação via {@code /numeros-0800/import}.
     */
    @GetMapping("/numeros-0800/template")
    public ResponseEntity<byte[]> templateNumeros0800() throws IOException {
        return xlsxResponse(excelService.templateNumeros0800(), "modelo-numeros-0800.xlsx");
    }

    /** Importa números 0800 em lote a partir de uma planilha XLSX preenchida a partir do modelo. */
    @PostMapping("/numeros-0800/import")
    public ResponseEntity<?> importNumeros0800(
            @RequestParam("file") MultipartFile file, HttpServletRequest req) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Arquivo vazio."));
        }
        CadastroExcelService.ImportResult<Numero0800> result;
        try {
            result = excelService.importNumeros0800(file);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        List<Numero0800> saved = numero0800Repo.saveAll(result.toSave());
        auditService.log(
                req,
                "CADASTRO_IMPORT",
                "Importação de Números 0800: "
                        + saved.size()
                        + " importados, "
                        + result.errors().size()
                        + " erros",
                true);
        return ResponseEntity.ok(
                Map.of(
                        "importados",
                        saved.size(),
                        "erros",
                        result.errors().size(),
                        "detalhes",
                        result.errors()));
    }

    // -----------------------------------------------------------------------
    // Linhas
    // -----------------------------------------------------------------------

    @GetMapping("/linhas")
    public ResponseEntity<List<Linha>> listLinhas(@RequestParam(required = false) Boolean active) {
        List<Linha> result =
                active != null ? linhaRepo.findByIsActive(active) : linhaRepo.findAll();
        return ResponseEntity.ok(
                filterByBusinessUnitScope(
                        result,
                        l -> l.getBusinessUnits().stream().map(BusinessUnit::getId).toList()));
    }

    @PostMapping("/linhas")
    public ResponseEntity<Linha> createLinha(
            @Valid @RequestBody Linha linha, HttpServletRequest req) {
        Linha saved = linhaRepo.save(linha);
        auditService.log(
                req,
                "CADASTRO_CREATE",
                "Linha criada: operadora '"
                        + saved.getOperadora().getNome()
                        + "' (id="
                        + saved.getId()
                        + ")",
                true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/linhas/{id}")
    public ResponseEntity<Linha> updateLinha(
            @PathVariable Integer id, @Valid @RequestBody Linha linha, HttpServletRequest req) {
        linha.setId(id);
        Linha saved = linhaRepo.save(linha);
        auditService.log(
                req,
                "CADASTRO_UPDATE",
                "Linha atualizada: operadora '"
                        + saved.getOperadora().getNome()
                        + "' (id="
                        + id
                        + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/linhas/{id}")
    public ResponseEntity<Void> deleteLinha(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "CADASTRO_DELETE", "Linha removida (id=" + id + ")", true);
        linhaRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sincroniza (substitui por completo) as Unidades de Negócio vinculadas a uma linha. Campo
     * opcional — lista vazia é válida e limpa a associação.
     */
    @PutMapping("/linhas/{id}/business-units")
    public ResponseEntity<?> syncLinhaBusinessUnits(
            @PathVariable Integer id,
            @RequestBody List<Integer> businessUnitIds,
            HttpServletRequest req) {
        var linhaOpt = linhaRepo.findById(id);
        if (linhaOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var resolved = resolveBusinessUnits(businessUnitIds);
        if (resolved.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Um ou mais IDs de Unidade de Negócio informados não existem."));
        }
        Linha linha = linhaOpt.get();
        linha.setBusinessUnits(resolved.get());
        Linha saved = linhaRepo.save(linha);
        auditService.log(
                req,
                "CADASTRO_UPDATE",
                "BUs da linha '" + saved.getOperadora().getNome() + "' atualizadas (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    /** Planilha XLSX com as linhas cadastradas, respeitando o escopo de BU do usuário. */
    @GetMapping("/linhas/export")
    public ResponseEntity<byte[]> exportLinhas() throws IOException {
        var items =
                filterByBusinessUnitScope(
                        linhaRepo.findAll(),
                        l -> l.getBusinessUnits().stream().map(BusinessUnit::getId).toList());
        return xlsxResponse(excelService.exportLinhas(items), "linhas.xlsx");
    }

    /** Modelo em branco para preenchimento e posterior importação via {@code /linhas/import}. */
    @GetMapping("/linhas/template")
    public ResponseEntity<byte[]> templateLinhas() throws IOException {
        return xlsxResponse(excelService.templateLinhas(), "modelo-linhas.xlsx");
    }

    /** Importa linhas em lote a partir de uma planilha XLSX preenchida a partir do modelo. */
    @PostMapping("/linhas/import")
    public ResponseEntity<?> importLinhas(
            @RequestParam("file") MultipartFile file, HttpServletRequest req) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Arquivo vazio."));
        }
        CadastroExcelService.ImportResult<Linha> result;
        try {
            result = excelService.importLinhas(file);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        List<Linha> saved = linhaRepo.saveAll(result.toSave());
        auditService.log(
                req,
                "CADASTRO_IMPORT",
                "Importação de Linhas: "
                        + saved.size()
                        + " importadas, "
                        + result.errors().size()
                        + " erros",
                true);
        return ResponseEntity.ok(
                Map.of(
                        "importados",
                        saved.size(),
                        "erros",
                        result.errors().size(),
                        "detalhes",
                        result.errors()));
    }

    private ResponseEntity<byte[]> xlsxResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /**
     * Controle de acesso por BU: mantém apenas os itens sem BU (visíveis a todos — BU é opcional no
     * cadastro) ou com ao menos uma BU em comum com o usuário logado. ADMIN não é filtrado.
     */
    private <T> List<T> filterByBusinessUnitScope(
            List<T> items, Function<T, List<Integer>> businessUnitIdsOf) {
        if (!BusinessUnitContext.isRestricted()) {
            return items;
        }
        var allowed = BusinessUnitContext.currentBusinessUnitIds();
        return items.stream()
                .filter(
                        item -> {
                            List<Integer> ids = businessUnitIdsOf.apply(item);
                            return ids.isEmpty() || ids.stream().anyMatch(allowed::contains);
                        })
                .toList();
    }

    /** Resolve os IDs de BusinessUnit informados; vazio se algum ID não existir. */
    private Optional<Set<BusinessUnit>> resolveBusinessUnits(List<Integer> businessUnitIds) {
        List<Integer> ids = businessUnitIds == null ? List.of() : businessUnitIds;
        List<BusinessUnit> found = buRepo.findAllById(ids);
        if (found.size() != Set.copyOf(ids).size()) {
            return Optional.empty();
        }
        return Optional.of(new HashSet<>(found));
    }
}
