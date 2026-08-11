package com.asteriskia.domain.connectivity;

import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import com.asteriskia.domain.masterdata.ClientRepository;
import com.asteriskia.domain.masterdata.OperationRepository;
import com.asteriskia.domain.masterdata.SegmentRepository;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * ConnectivityController — CRUD de testes de conectividade e resultados (Módulo 2).
 *
 * <p>Também consumido pelo Scheduler Python para: - GET /api/v1/number-tests?active=true → carrega
 * testes agendados - POST /api/v1/test-results → registra resultado de cada chamada
 *
 * <p>{@code @Transactional} em nível de classe: NumberTest/TestResult carregam Client/Operation,
 * que por sua vez têm businessUnits como coleção LAZY (V25) — sem sessão Hibernate aberta durante a
 * serialização do Jackson, o acesso lazy fora de transação lança LazyInitializationException
 * (spring.jpa.open-in-view=false neste projeto).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class ConnectivityController {

    private final NumberTestRepository numberTestRepo;
    private final TestResultRepository testResultRepo;
    private final SimpMessagingTemplate messagingTemplate;
    private final BusinessUnitRepository busRepo;
    private final ClientRepository clientRepo;
    private final OperationRepository operationRepo;
    private final SegmentRepository segmentRepo;

    // -----------------------------------------------------------------------
    // NumberTest — CRUD
    // -----------------------------------------------------------------------

    @GetMapping("/number-tests")
    public ResponseEntity<List<NumberTest>> listNumberTests(
            @RequestParam(required = false) Boolean active) {
        if (BusinessUnitContext.isRestricted()) {
            var buIds = BusinessUnitContext.currentBusinessUnitIds();
            List<NumberTest> result =
                    active != null
                            ? numberTestRepo.findByIsActiveAndBusinessUnit_IdIn(active, buIds)
                            : numberTestRepo.findByBusinessUnit_IdIn(buIds);
            return ResponseEntity.ok(result);
        }
        List<NumberTest> result =
                active != null ? numberTestRepo.findByIsActive(active) : numberTestRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/number-tests/{id}")
    public ResponseEntity<NumberTest> getNumberTest(@PathVariable Long id) {
        return numberTestRepo
                .findById(id)
                .filter(this::inBusinessUnitScope)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** true se o teste é visível ao usuário atual — ADMIN sempre vê tudo. */
    private boolean inBusinessUnitScope(NumberTest test) {
        return !BusinessUnitContext.isRestricted()
                || BusinessUnitContext.currentBusinessUnitIds()
                        .contains(test.getBusinessUnit().getId());
    }

    @PostMapping("/number-tests")
    public ResponseEntity<NumberTest> createNumberTest(@Valid @RequestBody NumberTest test) {
        return ResponseEntity.status(HttpStatus.CREATED).body(numberTestRepo.save(test));
    }

    @PutMapping("/number-tests/{id}")
    public ResponseEntity<NumberTest> updateNumberTest(
            @PathVariable Long id, @Valid @RequestBody NumberTest test) {
        test.setId(id);
        return ResponseEntity.ok(numberTestRepo.save(test));
    }

    @PatchMapping("/number-tests/{id}/active")
    @Transactional
    public ResponseEntity<Void> setActive(@PathVariable Long id, @RequestParam boolean active) {
        numberTestRepo
                .findById(id)
                .ifPresent(
                        t -> {
                            t.setIsActive(active);
                            numberTestRepo.save(t);
                        });
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/number-tests/{id}")
    public ResponseEntity<Void> deleteNumberTest(@PathVariable Long id) {
        numberTestRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // TestResult — Leitura e registro de resultados
    // -----------------------------------------------------------------------

    @GetMapping("/test-results")
    public ResponseEntity<Page<TestResult>> listResults(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long numberTestId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime dateTo,
            @RequestParam(required = false) Long businessUnitId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long operationId,
            @RequestParam(required = false) Long segmentId) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("executedAt").descending());
        // Controle de acesso por BU: allowedBusinessUnitIds intersecta a query com as
        // BUs do usuário logado — não confiar apenas no filtro opcional businessUnitId,
        // que o próprio cliente da requisição controla.
        java.util.List<Long> allowedBusinessUnitIds =
                BusinessUnitContext.isRestricted()
                        ? BusinessUnitContext.currentBusinessUnitIds().stream()
                                .map(Integer::longValue)
                                .toList()
                        : null;
        Page<TestResult> result =
                testResultRepo.findWithFilters(
                        numberTestId,
                        status,
                        dateFrom,
                        dateTo,
                        businessUnitId,
                        clientId,
                        operationId,
                        segmentId,
                        allowedBusinessUnitIds,
                        pageable);
        return ResponseEntity.ok(result);
    }

    /** Consumido pelo Scheduler Python para registrar cada resultado de chamada. */
    @PostMapping("/test-results")
    public ResponseEntity<TestResult> registerResult(@Valid @RequestBody TestResult result) {
        log.info(
                "Resultado de teste registrado: numberTest={} status={}",
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
