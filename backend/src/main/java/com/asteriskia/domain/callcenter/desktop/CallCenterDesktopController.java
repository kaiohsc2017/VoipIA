package com.asteriskia.domain.callcenter.desktop;

import com.asteriskia.domain.callcenter.reports.AgentProductivityReport;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterDesktopController — painel pessoal do agente (Fase 22). Todos os endpoints resolvem
 * o agente pelo usuário autenticado — nenhum aceita um identificador de agente vindo do chamador
 * (path/query/body), de propósito: é a diferença entre um painel pessoal e um vazamento de
 * produtividade de outro agente.
 */
@RestController
@RequestMapping("/api/v1/callcenter/desktop/me")
@RequiredArgsConstructor
public class CallCenterDesktopController {

    private final CallCenterDesktopService desktopService;

    @GetMapping("/resumo")
    public DesktopSummaryView resumo() {
        return desktopService.resumo();
    }

    @GetMapping("/historico")
    public List<DesktopCallHistoryItem> historico(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return desktopService.historico(de, ate);
    }

    @GetMapping("/pausas")
    public List<DesktopPauseItem> pausas() {
        return desktopService.pausas();
    }

    @GetMapping("/tendencia")
    public List<DesktopTrendPoint> tendencia(@RequestParam(defaultValue = "7") int dias) {
        return desktopService.tendencia(dias);
    }

    @GetMapping("/escala")
    public DesktopScheduleView escala(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        return desktopService.escala(data);
    }

    @GetMapping("/produtividade")
    public AgentProductivityReport produtividade(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return desktopService.produtividade(de, ate);
    }

    @GetMapping("/qualidade")
    public DesktopQualityView qualidade(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return desktopService.qualidade(de, ate);
    }

    @GetMapping("/ranking")
    public DesktopRankingView ranking(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return desktopService.ranking(de, ate);
    }
}
