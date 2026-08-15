package com.asteriskia.domain.callcenter.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ContactProfileService — fachada síncrona do copiloto de IA (Fase 16.2): nunca chama o LLM
 * diretamente (isso é sempre assíncrono, {@link ContactProfileGenerator}) — só decide se um
 * perfil já existe fresco o bastante para reusar, e dispara a geração em segundo plano quando não
 * há um, ou quando o existente está fora da janela de cache. O atendimento nunca espera por esta
 * chamada.
 *
 * <p>{@code inFlight} evita disparar múltiplas gerações concorrentes para o mesmo contato — sem
 * isso, o polling do frontend (a cada poucos segundos, enquanto {@code status=GENERATING})
 * dispararia uma chamada ao Gemini por requisição, multiplicando custo sem necessidade.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactProfileService {

    @Value("${app.callcenter.copiloto.cache-hours:24}")
    private long cacheHours;

    private final CcContactProfileRepository profileRepository;
    private final ContactProfileGenerator generator;
    private final CallCenterContactHistoryService historyService;
    private final ObjectMapper objectMapper;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** {@code resolvedAdSam} sempre resolvido pelo chamador a partir de uma interação/sessão já
     * validada como pertencente ao agente autenticado (mesma disciplina anti-IDOR de {@code
     * CallCenterInteractionService#contactHistory}, Fase 14) — este serviço nunca recebe o sam
     * direto de um parâmetro de requisição. */
    @Transactional(readOnly = true)
    public ContactProfileView getOrTrigger(String resolvedAdSam, Long interactionId) {
        if (resolvedAdSam == null || resolvedAdSam.isBlank()) {
            return ContactProfileView.unavailable();
        }
        var existing = profileRepository.findFirstByResolvedAdSamOrderByGeneratedAtDesc(resolvedAdSam).orElse(null);
        boolean fresh = existing != null
                && Duration.between(existing.getGeneratedAt(), LocalDateTime.now()).toHours() < cacheHours;

        if (existing == null || !fresh) {
            triggerGeneration(resolvedAdSam, interactionId);
        }
        if (existing == null) {
            return ContactProfileView.generating();
        }
        return ContactProfileView.ready(existing, parseContent(existing));
    }

    private void triggerGeneration(String resolvedAdSam, Long interactionId) {
        if (!inFlight.add(resolvedAdSam)) {
            return;
        }
        var history = historyService.historyFor(resolvedAdSam, 5, interactionId, null);
        // Removido de inFlight só quando a geração assíncrona de fato CONCLUI (sucesso ou
        // falha) — nunca logo após o disparo, senão o polling do frontend (a cada poucos
        // segundos enquanto status=GENERATING) reenfileiraria uma chamada ao Gemini por
        // requisição, multiplicando custo sem necessidade.
        generator.generate(resolvedAdSam, interactionId, history)
                .whenComplete((ignoredResult, ignoredError) -> inFlight.remove(resolvedAdSam));
    }

    private ContactProfileContent parseContent(CcContactProfile profile) {
        try {
            return objectMapper.readValue(profile.getProfileJson(), ContactProfileContent.class);
        } catch (Exception e) {
            log.warn("Perfil de IA persistido com JSON inválido (id={}) — tratando como vazio.", profile.getId());
            return new ContactProfileContent("", "", List.of(), java.math.BigDecimal.ZERO, List.of());
        }
    }
}
