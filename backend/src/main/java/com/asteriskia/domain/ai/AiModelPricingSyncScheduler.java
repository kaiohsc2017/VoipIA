package com.asteriskia.domain.ai;

import com.asteriskia.telegram.TelegramBotService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AiModelPricingSyncScheduler — atualiza {@code ai_model_pricing} diariamente com o preço
 * publicado pela Google, usado pra estimar o custo de IA (URA e Insights). Mirror de
 * {@code ConnectivityScheduler}/{@code JiraSyncScheduler} — job agendado + método público
 * reaproveitado pelo endpoint de disparo manual ({@code POST /api/v1/ai/model-pricing/sync-now}).
 *
 * <p><b>Nunca sobrescreve com preço zero/inválido</b>: {@link AiPricingSourceFetcher} só devolve
 * {@code success=true} depois de validar o valor (ver {@link AiPricingSourceFetcher#isPlausible});
 * em qualquer falha, o preço já cadastrado é mantido intocado e um alerta é enviado via Telegram
 * (mesmo canal já usado pelos alertas de Zabbix) — esse valor alimenta decisão de negócio, uma
 * defasagem silenciosa é pior do que um alerta de falha.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelPricingSyncScheduler {

    /** Modelos rastreados hoje pelo pipeline de custos de IA (URA + Insights). Adicionar um
     * modelo novo aqui basta — o id precisa bater com o `<h2 id=...>` da página de preços. */
    private static final List<String> TRACKED_MODEL_IDS =
            List.of("gemini-2.5-flash", "gemini-2.5-flash-preview-tts");

    /** Variação (fração, 0.30 = 30%) a partir da qual uma atualização bem-sucedida também gera
     * alerta — mesmo quando o fetch deu certo, uma mudança grande merece atenção humana. */
    private static final BigDecimal SIGNIFICANT_CHANGE_THRESHOLD = new BigDecimal("0.30");

    private final AiPricingSourceFetcher fetcher;
    private final AiModelPricingRepository repository;
    private final TelegramBotService telegramBotService;

    @Scheduled(cron = "${app.ai.pricing-sync-cron:0 0 2 * * ?}")
    public void scheduledSync() {
        run();
    }

    /** Executa o fetch + validação + persistência para todos os modelos rastreados. Público e
     * síncrono para ser reusado pelo endpoint manual de disparo. */
    public List<PricingFetchResult> run() {
        List<PricingFetchResult> results = fetcher.fetchAll(TRACKED_MODEL_IDS);
        for (PricingFetchResult result : results) {
            if (result.success()) {
                applySuccess(result);
            } else {
                applyFailure(result);
            }
        }
        return results;
    }

    private void applySuccess(PricingFetchResult result) {
        AiModelPricing existing = repository.findById(result.modelId()).orElse(null);
        boolean unchanged =
                existing != null
                        && existing.getPricePerMillionInputUsd().compareTo(result.pricePerMillionInputUsd()) == 0
                        && existing.getPricePerMillionOutputUsd().compareTo(result.pricePerMillionOutputUsd()) == 0;
        if (unchanged) {
            log.debug("Preço de {} sem mudança na busca automática — nada a atualizar", result.modelId());
            return;
        }

        boolean significant =
                existing != null
                        && (isSignificantChange(existing.getPricePerMillionInputUsd(), result.pricePerMillionInputUsd())
                                || isSignificantChange(
                                        existing.getPricePerMillionOutputUsd(), result.pricePerMillionOutputUsd()));

        AiModelPricing entity =
                existing != null
                        ? existing
                        : AiModelPricing.builder().modelId(result.modelId()).provider("gemini").build();
        BigDecimal previousInput = existing != null ? existing.getPricePerMillionInputUsd() : null;
        BigDecimal previousOutput = existing != null ? existing.getPricePerMillionOutputUsd() : null;
        entity.setPricePerMillionInputUsd(result.pricePerMillionInputUsd());
        entity.setPricePerMillionOutputUsd(result.pricePerMillionOutputUsd());
        entity.setUpdatedBy("auto-fetch");
        repository.save(entity);
        log.info(
                "Preço de {} atualizado automaticamente: input=${} output=${}",
                result.modelId(),
                result.pricePerMillionInputUsd(),
                result.pricePerMillionOutputUsd());

        if (significant) {
            telegramBotService.sendMessage(
                    String.format(
                            """
                            ⚠️ *Preço de IA mudou significativamente*
                            Modelo: `%s`
                            Input: $%s → $%s
                            Output: $%s → $%s
                            Atualizado automaticamente pela busca diária — confira se está correto em Configurações → IA.""",
                            result.modelId(),
                            previousInput,
                            result.pricePerMillionInputUsd(),
                            previousOutput,
                            result.pricePerMillionOutputUsd()));
        }
    }

    private void applyFailure(PricingFetchResult result) {
        log.error(
                "Falha ao buscar preço automático de {}: {} — preço atual mantido",
                result.modelId(),
                result.failureReason());
        telegramBotService.sendMessage(
                String.format(
                        """
                        ❌ *Falha ao buscar preço automático de IA*
                        Modelo: `%s`
                        Motivo: %s
                        O último preço válido foi mantido — nada foi sobrescrito. Considere revisar/corrigir manualmente em Configurações → IA.""",
                        result.modelId(), result.failureReason()));
    }

    private boolean isSignificantChange(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue == null || oldValue.compareTo(BigDecimal.ZERO) == 0) {
            return newValue.compareTo(BigDecimal.ZERO) != 0;
        }
        BigDecimal diffRatio = newValue.subtract(oldValue).abs().divide(oldValue, 4, RoundingMode.HALF_UP);
        return diffRatio.compareTo(SIGNIFICANT_CHANGE_THRESHOLD) > 0;
    }
}
