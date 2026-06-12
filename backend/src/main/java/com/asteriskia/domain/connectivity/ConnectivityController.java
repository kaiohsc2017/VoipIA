package com.asteriskia.domain.connectivity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ConnectivityController — CRUD de testes de conectividade e resultados (Módulo 2).
 *
 * Também consumido pelo Scheduler Python para:
 *   - GET  /api/v1/number-tests?active=true   → carrega testes agendados
 *   - POST /api/v1/test-results               → registra resultado de cada chamada
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Connectivity Tests", description = "Testes de conectividade telefônica (Módulo 2)")
public class ConnectivityController {

    private final NumberTestRepository numberTestRepo;
    private final TestResultRepository testResultRepo;
    private final SimpMessagingTemplate messagingTemplate;

    // -----------------------------------------------------------------------
    // NumberTest — CRUD
    // -----------------------------------------------------------------------

    @GetMapping("/number-tests")
    @Operation(summary = "Lista testes de conectividade")
    public ResponseEntity<List<NumberTest>> listNumberTests(
            @RequestParam(required = false) Boolean active) {
        List<NumberTest> result = active != null
                ? numberTestRepo.findByIsActive(active)
                : numberTestRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/number-tests/{id}")
    @Operation(summary = "Busca teste por ID")
    public ResponseEntity<NumberTest> getNumberTest(@PathVariable Long id) {
        return numberTestRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/number-tests")
    @Operation(summary = "Cria configuração de teste")
    public ResponseEntity<NumberTest> createNumberTest(@Valid @RequestBody NumberTest test) {
        return ResponseEntity.status(HttpStatus.CREATED).body(numberTestRepo.save(test));
    }

    @PutMapping("/number-tests/{id}")
    @Operation(summary = "Atualiza configuração de teste")
    public ResponseEntity<NumberTest> updateNumberTest(
            @PathVariable Long id, @Valid @RequestBody NumberTest test) {
        test.setId(id);
        return ResponseEntity.ok(numberTestRepo.save(test));
    }

    @PatchMapping("/number-tests/{id}/active")
    @Operation(summary = "Ativa/desativa teste")
    @Transactional
    public ResponseEntity<Void> setActive(@PathVariable Long id, @RequestParam boolean active) {
        numberTestRepo.findById(id).ifPresent(t -> {
            t.setIsActive(active);
            numberTestRepo.save(t);
        });
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/number-tests/{id}")
    @Operation(summary = "Remove configuração de teste")
    public ResponseEntity<Void> deleteNumberTest(@PathVariable Long id) {
        numberTestRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // TestResult — Leitura e registro de resultados
    // -----------------------------------------------------------------------

    @GetMapping("/test-results")
    @Operation(summary = "Lista resultados de testes (paginado, com filtros de período, status e dados mestres)")
    public ResponseEntity<Page<TestResult>> listResults(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    Long   numberTestId,
            @RequestParam(required = false)    String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(required = false)    Long   businessUnitId,
            @RequestParam(required = false)    Long   clientId,
            @RequestParam(required = false)    Long   operationId,
            @RequestParam(required = false)    Long   segmentId) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("executedAt").descending());
        Page<TestResult> result = testResultRepo.findWithFilters(
                numberTestId, status, dateFrom, dateTo,
                businessUnitId, clientId, operationId, segmentId,
                pageable);
        return ResponseEntity.ok(result);
    }

    /** Consumido pelo Scheduler Python para registrar cada resultado de chamada. */
    @PostMapping("/test-results")
    @Operation(summary = "Registra resultado de execução de teste")
    public ResponseEntity<TestResult> registerResult(@Valid @RequestBody TestResult result) {
        log.info("Resultado de teste registrado: numberTest={} status={}",
                result.getNumberTest() != null ? result.getNumberTest().getId() : "?",
                result.getStatus());

        TestResult saved = testResultRepo.save(result);
        try {
            messagingTemplate.convertAndSend("/topic/test-results", saved);
        } catch (Exception e) {
            log.warn("Erro ao enviar WebSocket de TestResult: {}", e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}

// ---------------------------------------------------------------------------
// Repositories
// ---------------------------------------------------------------------------

@Repository
interface NumberTestRepository extends JpaRepository<NumberTest, Long> {
    List<NumberTest> findByIsActive(Boolean isActive);
}

@Repository
interface TestResultRepository extends JpaRepository<TestResult, Long> {

    /**
     * Query unificada com todos os filtros opcionais.
     * Cada parâmetro é ignorado quando NULL — permite qualquer combinação de filtros
     * sem explodir em if/else.
     */
    @Query("SELECT r FROM TestResult r JOIN r.numberTest nt " +
           "WHERE (:numberTestId  IS NULL OR nt.id                  = :numberTestId) " +
           "AND   (:status        IS NULL OR r.status               = :status) " +
           "AND   (:dateFrom      IS NULL OR r.executedAt           >= :dateFrom) " +
           "AND   (:dateTo        IS NULL OR r.executedAt           <= :dateTo) " +
           "AND   (:businessUnitId IS NULL OR nt.businessUnit.id    = :businessUnitId) " +
           "AND   (:clientId      IS NULL OR nt.client.id           = :clientId) " +
           "AND   (:operationId   IS NULL OR nt.operation.id        = :operationId) " +
           "AND   (:segmentId     IS NULL OR nt.segment.id          = :segmentId)")
    Page<TestResult> findWithFilters(
            @Param("numberTestId")   Long          numberTestId,
            @Param("status")         String        status,
            @Param("dateFrom")       LocalDateTime dateFrom,
            @Param("dateTo")         LocalDateTime dateTo,
            @Param("businessUnitId") Long          businessUnitId,
            @Param("clientId")       Long          clientId,
            @Param("operationId")    Long          operationId,
            @Param("segmentId")      Long          segmentId,
            org.springframework.data.domain.Pageable pageable);

    // Mantidos para compatibilidade com HistoricoModal (filtra por numberTestId + período)
    Page<TestResult> findByNumberTestId(Long numberTestId, org.springframework.data.domain.Pageable pageable);
    Page<TestResult> findByNumberTestIdAndExecutedAtBetween(Long numberTestId, LocalDateTime from, LocalDateTime to, org.springframework.data.domain.Pageable pageable);

    // Estatísticas para o dashboard
    @Query("SELECT COUNT(r) FROM TestResult r WHERE r.executedAt BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(r) FROM TestResult r WHERE r.status = :status AND r.executedAt BETWEEN :from AND :to")
    long countByStatusAndPeriod(@Param("status") String status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
