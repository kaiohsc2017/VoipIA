package com.asteriskia.domain.masterdata;

import com.asteriskia.domain.audit.AuditService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.multipart.MultipartFile;
import com.asteriskia.domain.connectivity.NumberTest;
import com.asteriskia.domain.connectivity.NumberTestRepository;

/**
 * MasterDataController — CRUD de dados mestres (Módulo 2).
 * Agrupa os 4 recursos: BusinessUnit, Segment, Client, Operation.
 * Registra criações, atualizações e remoções no AuditLog (Fase 13).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Master Data", description = "CRUD de BU, Segmentos, Clientes e Operações (Módulo 2)")
public class MasterDataController {

    private final BusinessUnitRepository buRepo;
    private final SegmentRepository      segRepo;
    private final ClientRepository       clientRepo;
    private final OperationRepository    opRepo;
    private final AuditService           auditService;
    private final NumberTestRepository   numberTestRepo;

    // -----------------------------------------------------------------------
    // Business Units
    // -----------------------------------------------------------------------

    @GetMapping("/business-units")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Business Units")
    public ResponseEntity<List<BusinessUnit>> listBUs(@RequestParam(required = false) Boolean active) {
        List<BusinessUnit> result = active != null
                ? buRepo.findByIsActive(active)
                : buRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/business-units")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Business Unit")
    public ResponseEntity<BusinessUnit> createBU(@Valid @RequestBody BusinessUnit bu,
                                                  HttpServletRequest req) {
        BusinessUnit saved = buRepo.save(bu);
        auditService.log(req, "MASTERDATA_CREATE", "BusinessUnit criada: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/business-units/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Business Unit")
    public ResponseEntity<BusinessUnit> updateBU(@PathVariable Integer id, @Valid @RequestBody BusinessUnit bu,
                                                  HttpServletRequest req) {
        bu.setId(id);
        BusinessUnit saved = buRepo.save(bu);
        auditService.log(req, "MASTERDATA_UPDATE", "BusinessUnit atualizada: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/business-units/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Business Unit")
    public ResponseEntity<Void> deleteBU(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "BusinessUnit removida (id=" + id + ")", true);
        buRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Segments
    // -----------------------------------------------------------------------

    @GetMapping("/segments")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Segmentos")
    public ResponseEntity<List<Segment>> listSegments(@RequestParam(required = false) Boolean active) {
        List<Segment> result = active != null
                ? segRepo.findByIsActive(active)
                : segRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/segments")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Segmento")
    public ResponseEntity<Segment> createSegment(@Valid @RequestBody Segment seg, HttpServletRequest req) {
        Segment saved = segRepo.save(seg);
        auditService.log(req, "MASTERDATA_CREATE", "Segmento criado: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/segments/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Segmento")
    public ResponseEntity<Segment> updateSegment(@PathVariable Integer id, @Valid @RequestBody Segment seg,
                                                  HttpServletRequest req) {
        seg.setId(id);
        Segment saved = segRepo.save(seg);
        auditService.log(req, "MASTERDATA_UPDATE", "Segmento atualizado: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/segments/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Segmento")
    public ResponseEntity<Void> deleteSegment(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Segmento removido (id=" + id + ")", true);
        segRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Clients
    // -----------------------------------------------------------------------

    @GetMapping("/clients")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Clientes")
    public ResponseEntity<List<Client>> listClients(@RequestParam(required = false) Boolean active) {
        List<Client> result = active != null
                ? clientRepo.findByIsActive(active)
                : clientRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/clients")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Cliente")
    public ResponseEntity<Client> createClient(@Valid @RequestBody Client client, HttpServletRequest req) {
        Client saved = clientRepo.save(client);
        auditService.log(req, "MASTERDATA_CREATE", "Cliente criado: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/clients/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Cliente")
    public ResponseEntity<Client> updateClient(@PathVariable Integer id, @Valid @RequestBody Client client,
                                                HttpServletRequest req) {
        client.setId(id);
        Client saved = clientRepo.save(client);
        auditService.log(req, "MASTERDATA_UPDATE", "Cliente atualizado: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/clients/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Cliente")
    public ResponseEntity<Void> deleteClient(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Cliente removido (id=" + id + ")", true);
        clientRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Vincula operação a cliente (N:N). */
    @PostMapping("/clients/{clientId}/operations/{operationId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Vincula operação ao cliente")
    @Transactional
    public ResponseEntity<Void> addOperation(@PathVariable Integer clientId, @PathVariable Integer operationId,
                                              HttpServletRequest req) {
        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado: " + clientId));
        Operation op = opRepo.findById(operationId)
                .orElseThrow(() -> new RuntimeException("Operação não encontrada: " + operationId));
        client.getOperations().add(op);
        clientRepo.save(client);
        auditService.log(req, "MASTERDATA_UPDATE",
                "Operação '" + op.getName() + "' vinculada ao cliente '" + client.getName() + "'", true);
        return ResponseEntity.noContent().build();
    }

    /** Lista operações disponíveis para um cliente. */
    @GetMapping("/clients/{clientId}/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista operações vinculadas ao cliente")
    public ResponseEntity<List<Operation>> getClientOperations(@PathVariable Integer clientId) {
        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado: " + clientId));
        return ResponseEntity.ok(client.getOperations().stream().toList());
    }

    // -----------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------

    @GetMapping("/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Operações")
    public ResponseEntity<List<Operation>> listOps(@RequestParam(required = false) Boolean active) {
        List<Operation> result = active != null
                ? opRepo.findByIsActive(active)
                : opRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Operação")
    public ResponseEntity<Operation> createOp(@Valid @RequestBody Operation op, HttpServletRequest req) {
        Operation saved = opRepo.save(op);
        auditService.log(req, "MASTERDATA_CREATE", "Operação criada: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/operations/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Operação")
    public ResponseEntity<Operation> updateOp(@PathVariable Integer id, @Valid @RequestBody Operation op,
                                               HttpServletRequest req) {
        op.setId(id);
        Operation saved = opRepo.save(op);
        auditService.log(req, "MASTERDATA_UPDATE", "Operação atualizada: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/operations/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Operação")
    public ResponseEntity<Void> deleteOp(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Operação removida (id=" + id + ")", true);
        opRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
    // -----------------------------------------------------------------------
    // Importação em lote de Testes de Conectividade via CSV/XLSX
    // -----------------------------------------------------------------------

    /**
     * POST /api/v1/number-tests/import
     * Importa testes de conectividade a partir de CSV (separador ; , ou tab).
     * Colunas: numero | business_unit | cliente | operacao | segmento |
     *           horario_inicio | intervalo_minutos | quantidade | ativo
     */
    @PostMapping("/number-tests/import")
    @Transactional
    public ResponseEntity<?> importNumberTests(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest req) {

        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Arquivo vazio."));

        Map<String, BusinessUnit> buMap  = buRepo.findAll().stream()
                .collect(Collectors.toMap(b -> norm(b.getName()), Function.identity(), (a, b) -> a));
        Map<String, Client>       cliMap = clientRepo.findAll().stream()
                .collect(Collectors.toMap(c -> norm(c.getName()), Function.identity(), (a, b) -> a));
        Map<String, Operation>    opMap  = opRepo.findAll().stream()
                .collect(Collectors.toMap(o -> norm(o.getName()), Function.identity(), (a, b) -> a));
        Map<String, Segment>      segMap = segRepo.findAll().stream()
                .collect(Collectors.toMap(s -> norm(s.getName()), Function.identity(), (a, b) -> a));

        List<NumberTest>          saved  = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Arquivo sem cabeçalho."));

            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] cols = line.split("[;,\\t]", -1);
                try {
                    String phone   = col(cols, 0).replaceAll("[^+\\d]", "");
                    String buName  = col(cols, 1);
                    String cliName = col(cols, 2);
                    String opName  = col(cols, 3);
                    String segName = col(cols, 4);
                    String timeStr = col(cols, 5);
                    int interval   = Integer.parseInt(col(cols, 6).replaceAll("[^\\d]", ""));
                    int quantity   = Integer.parseInt(col(cols, 7).replaceAll("[^\\d]", ""));
                    String actStr  = col(cols, 8);

                    if (phone.isBlank())         throw new IllegalArgumentException("Número vazio");
                    BusinessUnit bu  = buMap.get(norm(buName));
                    Client       cli = cliMap.get(norm(cliName));
                    Operation    op  = opMap.get(norm(opName));
                    Segment      seg = segMap.get(norm(segName));
                    if (bu  == null) throw new IllegalArgumentException("BU não encontrada: '" + buName + "'");
                    if (cli == null) throw new IllegalArgumentException("Cliente não encontrado: '" + cliName + "'");
                    if (op  == null) throw new IllegalArgumentException("Operação não encontrada: '" + opName + "'");
                    if (seg == null) throw new IllegalArgumentException("Segmento não encontrado: '" + segName + "'");

                    String t = timeStr.trim();
                    if (t.matches("\\d{1,2}:\\d{2}")) t += ":00";
                    boolean active = !"false".equalsIgnoreCase(actStr.trim())
                                  && !"nao".equals(norm(actStr.trim()))
                                  && !"0".equals(actStr.trim());

                    saved.add(numberTestRepo.save(NumberTest.builder()
                            .phoneNumber(phone).businessUnit(bu).client(cli)
                            .operation(op).segment(seg).startTime(LocalTime.parse(t))
                            .intervalMinutes(interval).quantity(quantity).isActive(active)
                            .build()));

                } catch (Exception e) {
                    errors.add(Map.of("linha", lineNumber, "conteudo", line, "erro", e.getMessage()));
                }
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Erro ao processar arquivo: " + e.getMessage()));
        }

        auditService.log(req, "NUMBER_TEST_IMPORT",
                "Importação: " + saved.size() + " importados, " + errors.size() + " erros", true);

        return ResponseEntity.ok(Map.of(
                "importados", saved.size(),
                "erros",      errors.size(),
                "detalhes",   errors));
    }

    private static String col(String[] cols, int i) {
        return (i < cols.length) ? cols[i].trim().replaceAll("^"|"$", "") : "";
    }

    private static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("\\s+", " ");
    }


}

// ---------------------------------------------------------------------------
// Repositories — simples, no mesmo arquivo por coesão
// ---------------------------------------------------------------------------

@Repository
interface BusinessUnitRepository extends JpaRepository<BusinessUnit, Integer> {
    List<BusinessUnit> findByIsActive(Boolean isActive);
}

@Repository
interface SegmentRepository extends JpaRepository<Segment, Integer> {
    List<Segment> findByIsActive(Boolean isActive);
}

@Repository
interface ClientRepository extends JpaRepository<Client, Integer> {
    List<Client> findByIsActive(Boolean isActive);
}

@Repository
interface OperationRepository extends JpaRepository<Operation, Integer> {
    List<Operation> findByIsActive(Boolean isActive);
}
