package com.asteriskia.domain.ai;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * AiPricingSourceFetcher — busca o preço por milhão de tokens (tier "Standard", preço pago) de
 * modelos Gemini na página pública {@code ai.google.dev/gemini-api/docs/pricing}.
 *
 * <p><b>Não existe API oficial de preços da Google</b> (confirmado por pesquisa antes desta
 * entrega) — esta é a única fonte pública disponível, em HTML puro, sujeita a mudar de estrutura
 * sem aviso. Por isso todo resultado é validado antes de ser aceito (ver {@link #isPlausible}) e
 * qualquer falha de parsing vira {@link PricingFetchResult#fail} em vez de propagar um valor
 * duvidoso — quem grava o preço ({@link AiModelPricingSyncScheduler}) nunca sobrescreve com um
 * resultado de falha.
 *
 * <p>Cada modelo tem um {@code <h2 id="{modelId}">} na página (o id bate exatamente com o
 * `model_id` usado em `ai_model_pricing`), dentro de um {@code <div class="models-section">} que
 * fecha logo após o cabeçalho — a tabela de preços fica em divs **irmãs seguintes** (descrição do
 * modelo + bloco `<devsite-selector>` com uma `<section>` por "tier": Standard/Batch/Flex/
 * Priority). Por isso a busca anda pelos irmãos a partir do `models-section` até achar o primeiro
 * bloco com `<section><h3>`, parando se esbarrar no `models-section` do próximo modelo antes
 * disso. Só o tier "Standard" é usado — é o preço da API padrão (mesma usada pelo
 * `ai-agent`/`insights`).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPricingSourceFetcher {

    private static final String PRICING_URL = "https://ai.google.dev/gemini-api/docs/pricing";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal MIN_PLAUSIBLE_USD_PER_MILLION = new BigDecimal("0.01");
    private static final BigDecimal MAX_PLAUSIBLE_USD_PER_MILLION = new BigDecimal("100");
    private static final Pattern DOLLAR_VALUE = Pattern.compile("\\$([0-9]+(?:\\.[0-9]+)?)");
    private static final int MAX_SIBLING_HOPS = 10;

    private final WebClient.Builder webClientBuilder;

    /** Busca a página uma única vez e resolve o preço de cada modelo pedido — 1 request HTTP
     * total, não 1 por modelo. */
    public List<PricingFetchResult> fetchAll(List<String> modelIds) {
        String html;
        try {
            html =
                    webClientBuilder
                            .build()
                            .get()
                            .uri(PRICING_URL)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(FETCH_TIMEOUT)
                            .block();
        } catch (Exception e) {
            log.error("Falha de rede ao buscar página de preços da Google: {}", e.getMessage());
            return modelIds.stream()
                    .map(id -> PricingFetchResult.fail(id, "falha de rede: " + e.getMessage()))
                    .toList();
        }

        if (html == null || html.isBlank()) {
            return modelIds.stream()
                    .map(id -> PricingFetchResult.fail(id, "resposta vazia da página de preços"))
                    .toList();
        }

        Document doc = Jsoup.parse(html, PRICING_URL);
        return modelIds.stream().map(id -> fetchOne(doc, id)).toList();
    }

    private PricingFetchResult fetchOne(Document doc, String modelId) {
        Element heading = doc.getElementById(modelId);
        if (heading == null) {
            return PricingFetchResult.fail(
                    modelId, "modelo não encontrado na página (id=\"" + modelId + "\" ausente — página pode ter mudado)");
        }

        Element modelsSectionDiv = heading.closest("div.models-section");
        if (modelsSectionDiv == null) {
            return PricingFetchResult.fail(
                    modelId, "estrutura da página mudou — não encontrou o bloco de cabeçalho do modelo");
        }

        Element pricingContainer = findPricingContainer(modelsSectionDiv);
        if (pricingContainer == null) {
            return PricingFetchResult.fail(
                    modelId, "estrutura da página mudou — não encontrou o bloco de tabelas de preço");
        }

        Element standardHeading =
                pricingContainer.select("h3").stream()
                        .filter(h3 -> "standard".equalsIgnoreCase(h3.text().trim()))
                        .findFirst()
                        .orElse(null);
        if (standardHeading == null || standardHeading.parent() == null) {
            return PricingFetchResult.fail(modelId, "não encontrou a seção de preços \"Standard\"");
        }

        Element table = standardHeading.parent().selectFirst("table.pricing-table");
        if (table == null) {
            return PricingFetchResult.fail(modelId, "não encontrou a tabela de preços da seção Standard");
        }

        BigDecimal input = null;
        BigDecimal output = null;
        for (Element row : table.select("tbody tr")) {
            Elements cells = row.select("td");
            if (cells.size() < 3) continue;
            String label = cells.get(0).text().trim().toLowerCase();
            BigDecimal value = firstDollarValue(cells.get(2).text());
            if (value == null) continue;
            if (label.startsWith("input price")) {
                input = value;
            } else if (label.startsWith("output price")) {
                output = value;
            }
        }

        if (input == null || output == null) {
            return PricingFetchResult.fail(
                    modelId, "não encontrou preço de input/output na tabela (input=" + input + ", output=" + output + ")");
        }
        if (!isPlausible(input) || !isPlausible(output)) {
            return PricingFetchResult.fail(
                    modelId,
                    "valor fora da faixa plausível — descartado por segurança (input=" + input + ", output=" + output + ")");
        }

        return PricingFetchResult.ok(modelId, input, output);
    }

    /** A partir do {@code div.models-section} (só o cabeçalho do modelo), anda pelos irmãos
     * seguintes até achar o bloco que contém as tabelas de preço (tem {@code <section><h3>}
     * dentro) — para se esbarrar no {@code models-section} do próximo modelo antes disso (limite
     * de segurança pra nunca ficar preso num loop se a página mudar de forma inesperada). */
    private Element findPricingContainer(Element modelsSectionDiv) {
        Element sibling = modelsSectionDiv.nextElementSibling();
        int hops = 0;
        while (sibling != null && !sibling.hasClass("models-section") && hops < MAX_SIBLING_HOPS) {
            if (!sibling.select("section h3").isEmpty()) {
                return sibling;
            }
            sibling = sibling.nextElementSibling();
            hops++;
        }
        return null;
    }

    private BigDecimal firstDollarValue(String cellText) {
        Matcher matcher = DOLLAR_VALUE.matcher(cellText);
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isPlausible(BigDecimal value) {
        return value.compareTo(MIN_PLAUSIBLE_USD_PER_MILLION) >= 0
                && value.compareTo(MAX_PLAUSIBLE_USD_PER_MILLION) <= 0;
    }
}
