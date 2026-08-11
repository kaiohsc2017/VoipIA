package com.asteriskia.domain.callcenter.flow.engine;

import com.asteriskia.domain.callcenter.flow.CallCenterFlowService;
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

/**
 * CallCenterFlowExecutionController — traço de execuções reais do fluxo (Fase 5b): "onde o
 * cliente abandonou". Mesmo RBAC de leitura de {@code /fluxos} ({@code PERM_READ_callcenter.fluxos})
 * — reusa {@link CallCenterFlowService#findById} para aplicar o mesmo guard de escopo por BU antes
 * de expor qualquer execução do fluxo.
 */
@RestController
@RequestMapping("/api/v1/callcenter/fluxos")
@RequiredArgsConstructor
public class CallCenterFlowExecutionController {

    private final CallCenterFlowService flowService;
    private final CcFlowExecutionRepository executionRepository;
    private final CcFlowExecutionStepRepository stepRepository;

    @GetMapping("/{flowId}/execucoes")
    public ResponseEntity<Page<FlowExecutionView>> list(
            @PathVariable Long flowId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        flowService.findById(flowId); // guard de escopo por BU
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        return ResponseEntity.ok(executionRepository.findByFlowIdOrderByStartedAtDesc(flowId, pageable).map(FlowExecutionView::from));
    }

    @GetMapping("/{flowId}/execucoes/{executionId}/passos")
    public ResponseEntity<List<FlowExecutionStepView>> steps(@PathVariable Long flowId, @PathVariable Long executionId) {
        flowService.findById(flowId); // guard de escopo por BU
        executionRepository
                .findByIdAndFlowId(executionId, flowId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Execução " + executionId + " não pertence a este fluxo."));
        return ResponseEntity.ok(
                stepRepository.findByExecutionIdOrderByEnteredAtAsc(executionId).stream().map(FlowExecutionStepView::from).toList());
    }
}
