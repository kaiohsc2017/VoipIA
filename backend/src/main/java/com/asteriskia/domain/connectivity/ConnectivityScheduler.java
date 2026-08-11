package com.asteriskia.domain.connectivity;

import com.asteriskia.integration.ami.AmiOriginateService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ConnectivityScheduler — Testes periódicos de conectividade (Módulo 2).
 *
 * <p>Substitui o container Python 'scheduler' — mesma lógica, integrada ao backend Spring Boot.
 *
 * <p>Fluxo: 1. A cada poll-interval-secs, busca NumberTests ativos 2. Para cada teste, verifica
 * janela de horário e intervalo 3. Origina chamada via AMI (AmiOriginateService) 4. Registra
 * resultado no banco (TestResult)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectivityScheduler {

    private final NumberTestRepository numberTestRepo;
    private final TestResultRepository testResultRepo;
    private final AmiOriginateService amiService;

    // Estado em memória: testId → {count, lastCall, lastDate}
    private final Map<Long, TestState> testStates = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms:60000}")
    public void runCycle() {
        List<NumberTest> tests = numberTestRepo.findByIsActive(true);
        if (tests.isEmpty()) return;
        log.debug("Scheduler: {} testes ativos", tests.size());
        for (NumberTest test : tests) {
            try {
                maybeRunTest(test);
            } catch (Exception e) {
                log.error("Erro no teste id={}: {}", test.getId(), e.getMessage());
            }
        }
    }

    private void maybeRunTest(NumberTest test) {
        Long testId = test.getId();
        TestState state = testStates.computeIfAbsent(testId, id -> new TestState());
        LocalDateTime now = LocalDateTime.now();

        // Verifica horário de início
        if (test.getStartTime() != null && now.toLocalTime().isBefore(test.getStartTime())) {
            return;
        }

        // Reseta contador no novo dia
        if (state.lastDate != null && state.lastDate.isBefore(now.toLocalDate())) {
            state.count = 0;
            state.lastDate = now.toLocalDate();
        }

        // Verifica quantidade máxima do dia
        int quantity = test.getQuantity() != null ? test.getQuantity() : 1;
        if (state.count >= quantity) return;

        // Verifica intervalo entre chamadas
        int intervalMin = test.getIntervalMinutes() != null ? test.getIntervalMinutes() : 60;
        if (state.lastCall != null) {
            long elapsed = java.time.Duration.between(state.lastCall, now).toMinutes();
            if (elapsed < intervalMin) return;
        }

        // Executa
        int executionOrder = state.count + 1;
        log.info(
                "Teste {} → {} (execução {}/{})",
                testId,
                test.getPhoneNumber(),
                executionOrder,
                quantity);

        // Cria TestResult pendente para passar o ID ao AMI
        TestResult pending =
                TestResult.builder()
                        .numberTest(test)
                        .executedAt(now)
                        .status("EXECUTANDO")
                        .executionOrder(executionOrder)
                        .build();
        pending = testResultRepo.save(pending);

        boolean success = false;
        try {
            success = amiService.originateTestCall(test.getPhoneNumber(), pending.getId());
        } catch (Exception e) {
            log.error("Falha AMI teste {}: {}", testId, e.getMessage());
        }

        // Atualiza status (o resultado SIP real chega via webhook do dialplan)
        pending.setStatus(success ? "DISCANDO" : "ERRO_AMI");
        testResultRepo.save(pending);

        state.count = executionOrder;
        state.lastCall = now;
        state.lastDate = now.toLocalDate();

        log.info("Teste {} → {}", testId, pending.getStatus());
    }

    private static class TestState {
        int count = 0;
        LocalDateTime lastCall = null;
        LocalDate lastDate = null;
    }
}
