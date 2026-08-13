package com.asteriskia.domain.callcenter.nps;

import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.CcSettingsService;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * CallCenterNpsExecutionService — disparo direto da pesquisa de satisfação pós-fila (Fase 21,
 * §21.2). Diferente do nó {@code pesquisa_satisfacao} (dentro de um fluxo comum autoral), este
 * caminho é acionado pelo próprio dialplan: {@code Queue(F(nps,${EXTEN},1))} manda o cliente para
 * o contexto {@code [nps]} quando o AGENTE desliga (não o cliente) — o canal entra em Stasis com
 * o argumento {@code "nps-&lt;ramal da fila&gt;"}, e {@code AriEventListener} chama {@link #start}
 * em vez de {@code FlowExecutionEngine.start}.
 *
 * <p><b>Nunca bloqueante</b> (§4.2): qualquer caminho aqui termina em {@code driver.end()} —
 * sem pesquisa configurada, pesquisa/fila inválida, ou interruptor global desligado, a chamada é
 * só encerrada, sem tentar nada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterNpsExecutionService {

    private final CcQueueRepository queueRepository;
    private final CcSettingsService settingsService;
    private final CallCenterSurveyRunner runner;

    public void start(String channelId, String queueExtension, ChannelDriver driver) {
        try {
            run(channelId, queueExtension, driver);
        } catch (Exception e) {
            log.warn("Falha ao executar pesquisa de NPS pós-fila (fila={}): {}", queueExtension, e.getMessage());
        } finally {
            driver.end();
        }
    }

    private void run(String channelId, String queueExtension, ChannelDriver driver) {
        if (!settingsService.isNpsEnabledGlobally()) {
            return;
        }
        var queue = queueRepository.findByName(queueExtension).orElse(null);
        if (queue == null || queue.getSurvey() == null || !Boolean.TRUE.equals(queue.getSurvey().getActive())) {
            return;
        }
        runner.run(queue.getSurvey(), driver, channelId, queue);
    }
}
