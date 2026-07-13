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
 * LinhaController — CRUD do cadastro "Linhas" (bloco Cadastros), extraído de CadastroController
 * (fase 14 da refatoração) junto com {@link Numero0800Controller}.
 *
 * <p>{@code @Transactional} em nível de classe: Linha carrega businessUnits como coleção EAGER e é
 * serializada diretamente pelo Jackson — sem uma sessão Hibernate aberta durante a serialização, o
 * acesso à coleção fora de transação lança LazyInitializationException
 * (spring.jpa.open-in-view=false neste projeto).
 */
@RestController
@RequestMapping("/api/v1/linhas")
@RequiredArgsConstructor
@Transactional
public class LinhaController {

    private final LinhaRepository linhaRepo;
    private final BusinessUnitRepository buRepo;
    private final AuditService auditService;
    private final CadastroExcelService excelService;

    @GetMapping
    public ResponseEntity<List<Linha>> listLinhas(@RequestParam(required = false) Boolean active) {
        List<Linha> result =
                active != null ? linhaRepo.findByIsActive(active) : linhaRepo.findAll();
        return ResponseEntity.ok(
                MasterDataScopeFilter.filterByBusinessUnitScope(
                        result,
                        l -> l.getBusinessUnits().stream().map(BusinessUnit::getId).toList()));
    }

    @PostMapping
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

    @PutMapping("/{id}")
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLinha(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "CADASTRO_DELETE", "Linha removida (id=" + id + ")", true);
        linhaRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sincroniza (substitui por completo) as Unidades de Negócio vinculadas a uma linha. Campo
     * opcional — lista vazia é válida e limpa a associação.
     */
    @PutMapping("/{id}/business-units")
    public ResponseEntity<?> syncLinhaBusinessUnits(
            @PathVariable Integer id,
            @RequestBody List<Integer> businessUnitIds,
            HttpServletRequest req) {
        var linhaOpt = linhaRepo.findById(id);
        if (linhaOpt.isEmpty()) {
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
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportLinhas() throws IOException {
        var items =
                MasterDataScopeFilter.filterByBusinessUnitScope(
                        linhaRepo.findAll(),
                        l -> l.getBusinessUnits().stream().map(BusinessUnit::getId).toList());
        return CadastroSupport.xlsxResponse(excelService.exportLinhas(items), "linhas.xlsx");
    }

    /** Modelo em branco para preenchimento e posterior importação via {@code /linhas/import}. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> templateLinhas() throws IOException {
        return CadastroSupport.xlsxResponse(excelService.templateLinhas(), "modelo-linhas.xlsx");
    }

    /** Importa linhas em lote a partir de uma planilha XLSX preenchida a partir do modelo. */
    @PostMapping("/import")
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
}
