package com.asteriskia.domain.cadastro;

import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import com.asteriskia.domain.masterdata.MasterDataScopeFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Numero0800Controller — CRUD do cadastro "Números 0800" (bloco Cadastros), extraído de
 * CadastroController (fase 14 da refatoração) junto com {@link LinhaController}.
 *
 * <p>{@code @Transactional} em nível de classe: Numero0800 carrega businessUnits e regenerados como
 * coleção EAGER e é serializado diretamente pelo Jackson — sem uma sessão Hibernate aberta durante
 * a serialização, o acesso à coleção fora de transação lança LazyInitializationException
 * (spring.jpa.open-in-view=false neste projeto).
 */
@RestController
@RequestMapping("/api/v1/numeros-0800")
@RequiredArgsConstructor
@Transactional
public class Numero0800Controller {

    private final Numero0800Repository numero0800Repo;
    private final BusinessUnitRepository buRepo;
    private final AuditService auditService;
    private final CadastroExcelService excelService;

    @GetMapping
    public ResponseEntity<List<Numero0800>> listNumeros0800(
            @RequestParam(required = false) Boolean active) {
        List<Numero0800> result =
                active != null ? numero0800Repo.findByIsActive(active) : numero0800Repo.findAll();
        return ResponseEntity.ok(
                MasterDataScopeFilter.filterByBusinessUnitScope(
                        result,
                        n -> n.getBusinessUnits().stream().map(BusinessUnit::getId).toList()));
    }

    @PostMapping
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

    @PutMapping("/{id}")
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNumero0800(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "CADASTRO_DELETE", "Número 0800 removido (id=" + id + ")", true);
        numero0800Repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sincroniza (substitui por completo) as Unidades de Negócio vinculadas a um número 0800. Campo
     * opcional — lista vazia é válida e limpa a associação.
     */
    @PutMapping("/{id}/business-units")
    public ResponseEntity<?> syncNumero0800BusinessUnits(
            @PathVariable Integer id,
            @RequestBody List<Integer> businessUnitIds,
            HttpServletRequest req) {
        var numero0800Opt = numero0800Repo.findById(id);
        if (numero0800Opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var resolved = CadastroSupport.resolveBusinessUnits(buRepo, businessUnitIds);
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
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportNumeros0800() throws IOException {
        var items =
                MasterDataScopeFilter.filterByBusinessUnitScope(
                        numero0800Repo.findAll(),
                        n -> n.getBusinessUnits().stream().map(BusinessUnit::getId).toList());
        return CadastroSupport.xlsxResponse(
                excelService.exportNumeros0800(items), "numeros-0800.xlsx");
    }

    /**
     * Modelo em branco para preenchimento e posterior importação via {@code /numeros-0800/import}.
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> templateNumeros0800() throws IOException {
        return CadastroSupport.xlsxResponse(
                excelService.templateNumeros0800(), "modelo-numeros-0800.xlsx");
    }

    /** Importa números 0800 em lote a partir de uma planilha XLSX preenchida a partir do modelo. */
    @PostMapping("/import")
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
}
