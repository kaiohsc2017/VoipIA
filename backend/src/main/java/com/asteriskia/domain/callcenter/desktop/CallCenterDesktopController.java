package com.asteriskia.domain.callcenter.desktop;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public List<DesktopCallHistoryItem> historico() {
        return desktopService.historico();
    }

    @GetMapping("/pausas")
    public List<DesktopPauseItem> pausas() {
        return desktopService.pausas();
    }
}
