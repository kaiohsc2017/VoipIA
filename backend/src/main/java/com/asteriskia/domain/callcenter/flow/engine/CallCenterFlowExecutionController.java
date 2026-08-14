package com.asteriskia.domain.callcenter.flow.engine;

import com.asteriskia.domain.callcenter.flow.CallCenterFlowService;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * CallCenterFlowExecutionController — traço de execuções reais do fluxo (Fase 5b/5f.2): "onde o
 * cliente abandonou". Mesmo RBAC de leitura de {@code /fluxos} ({@code PERM_READ_callcenter.fluxos})
 * — reusa {@link CallCenterFlowService#findById} para aplicar o mesmo guard de escopo por BU antes
 * de expor qualquer execução do fluxo.
 *
 * <p>Fase 5f.2: período ({@code from}/{@code to}) passou a ser obrigatório na listagem, e os
 * passos de uma execução são buscados dentro da janela real da própria execução (nunca por
 * {@code executionId} isolado) — {@code cc_flow_execution_steps} é particionada por mês em
 * {@code entered_at} (migration V72), e uma busca sem esse filtro varreria todas as partições.
 */
@RestController
@RequestMapping("/api/v1/callcenter/fluxos")
@RequiredArgsConstructor
public class CallCenterFlowExecutionController {

    /** Folga aplicada à janela de startedAt/endedAt da execução ao consultar os passos — cobre o
     * caso raro de relógio ligeiramente dessincronizado entre o registro do passo e o da execução
     * (mesmo write, mas em transações/instantes distintos). */
    private static final long STEP_WINDOW_SLACK_MINUTES = 15;

    private final CallCenterFlowService flowService;
    private final CcFlowExecutionRepository executionRepository;
    private final CcFlowExecutionStepRepository stepRepository;

    @GetMapping("/{flowId}/execucoes")
    public ResponseEntity<Page<FlowExecutionView>> list(
            @PathVariable Long flowId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        flowService.findById(flowId); // guard de escopo por BU
        var range = parseRange(from, to);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        return ResponseEntity.ok(
                executionRepository
                        .findByFlowIdAndStartedAtBetweenOrderByStartedAtDesc(flowId, range[0], range[1], pageable)
                        .map(FlowExecutionView::from));
    }

    @GetMapping("/{flowId}/execucoes/{executionId}/passos")
    public ResponseEntity<List<FlowExecutionStepView>> steps(@PathVariable Long flowId, @PathVariable Long executionId) {
        flowService.findById(flowId); // guard de escopo por BU
        var execution =
                executionRepository
                        .findByIdAndFlowId(executionId, flowId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Execução " + executionId + " não pertence a este fluxo."));
        var slackStart = execution.getStartedAt().minusMinutes(STEP_WINDOW_SLACK_MINUTES);
        var slackEnd =
                (execution.getEndedAt() != null ? execution.getEndedAt() : LocalDateTime.now())
                        .plusMinutes(STEP_WINDOW_SLACK_MINUTES);
        return ResponseEntity.ok(
                stepRepository.findByExecutionIdAndEnteredAtBetweenOrderByEnteredAtAsc(executionId, slackStart, slackEnd).stream()
                        .map(FlowExecutionStepView::from)
                        .toList());
    }

    private LocalDateTime[] parseRange(String from, String to) {
        try {
            var parsedFrom = LocalDateTime.parse(from);
            var parsedTo = LocalDateTime.parse(to);
            if (parsedTo.isBefore(parsedFrom)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' não pode ser anterior a 'from'.");
            }
            return new LocalDateTime[] {parsedFrom, parsedTo};
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período inválido — use o formato ISO (ex: 2026-08-01T00:00:00).");
        }
    }
}
