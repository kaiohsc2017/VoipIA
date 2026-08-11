package com.asteriskia.domain.masterdata;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * NumberTestImportController — importação em lote de Testes de Conectividade via CSV/XLSX, extraído
 * de MasterDataController (fase 10 da refatoração). Delega o parsing para NumberTestImportService
 * (já extraído na fase 5).
 */
@RestController
@RequestMapping("/api/v1/number-tests")
@RequiredArgsConstructor
@Transactional
public class NumberTestImportController {

    private final NumberTestImportService numberTestImportService;
    private final AuditService auditService;

    /**
     * POST /api/v1/number-tests/import Importa testes de conectividade a partir de CSV (separador ;
     * , ou tab). Colunas: numero | business_unit | cliente | operacao | segmento | horario_inicio |
     * intervalo_minutos | quantidade | ativo
     */
    @PostMapping("/import")
    public ResponseEntity<?> importNumberTests(
            @RequestParam("file") MultipartFile file, HttpServletRequest req) {

        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Arquivo vazio."));

        NumberTestImportService.ImportResult result;
        try {
            result = numberTestImportService.importFromCsv(file);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Erro ao processar arquivo: " + e.getMessage()));
        }

        auditService.log(
                req,
                "NUMBER_TEST_IMPORT",
                "Importação: "
                        + result.saved().size()
                        + " importados, "
                        + result.errors().size()
                        + " erros",
                true);

        return ResponseEntity.ok(
                Map.of(
                        "importados", result.saved().size(),
                        "erros", result.errors().size(),
                        "detalhes", result.errors()));
    }
}
