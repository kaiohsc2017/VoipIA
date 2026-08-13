package com.asteriskia.domain.callcenter;

import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CcSettingsService — leitura/escrita das configurações do módulo Call Center (Fase 19 do plano
 * Parte III): ranges de ramal de agente/fila/fluxo e o interruptor global de pesquisa de
 * satisfação (NPS, consumido pela Fase 21).
 *
 * <p>Mudar um range aqui <b>não realoca nada</b> (D20 do plano) — vale só para as próximas
 * alocações de {@link CallCenterAgentProvisioningService}/{@link CallCenterQueueService}. Quem já
 * tem ramal fora da faixa nova continua com o ramal que tem.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcSettingsService {

    /** Um range de ramal fecha exatamente um bloco de milhar (ex.: 4000-4999) — é o único formato
     * que o padrão {@code _NXXX} do dialplan roteia sem editar {@code extensions.conf.template}. */
    public record ExtensionRange(int start, int end) {}

    public enum RangeType {
        AGENT("extension_range.agent", 4000, 4999, "agentes"),
        QUEUE("extension_range.queue", 5000, 5999, "filas"),
        FLOW("extension_range.flow", 6000, 6999, "fluxos");

        final String keyPrefix;
        final int defaultStart;
        final int defaultEnd;
        final String label;

        RangeType(String keyPrefix, int defaultStart, int defaultEnd, String label) {
            this.keyPrefix = keyPrefix;
            this.defaultStart = defaultStart;
            this.defaultEnd = defaultEnd;
            this.label = label;
        }
    }

    // Faixas do Telecom (fora do Call Center) que já ocupam dígitos de milhar — nenhum range do
    // Call Center pode colidir com elas: 1XXX = URA legada, 2XXX = URAs multi-instância,
    // 9XXX = softphones/ramais internos (ver CLAUDE.md — "Módulos do sistema Telecom").
    private static final Set<Integer> TELECOM_RESERVED_THOUSANDS = Set.of(1, 2, 9);

    private static final String NPS_ENABLED_KEY = "nps.enabled_globally";

    private final CcSettingRepository settingRepository;
    private final CcExtensionRepository extensionRepository;
    private final CcQueueRepository queueRepository;
    private final CcFlowRepository flowRepository;

    @Transactional(readOnly = true)
    public ExtensionRange getRange(RangeType type) {
        int start = getInt(type.keyPrefix + ".start", type.defaultStart);
        int end = getInt(type.keyPrefix + ".end", type.defaultEnd);
        return new ExtensionRange(start, end);
    }

    /**
     * Atualiza o range de {@code type}. Não migra nem realoca nenhum ramal existente (D20) — só
     * passa a valer para a próxima alocação.
     *
     * @return quantos ramais/filas/fluxos ativos hoje ficam fora da nova faixa, para a tela
     *     avisar o operador antes de confirmar.
     */
    @Transactional
    public int updateRange(RangeType type, int start, int end) {
        validateRangeShape(type, start, end);
        validateNoCollision(type, start, end);

        int outOfRangeCount = countActiveOutsideRange(type, start, end);

        upsert(type.keyPrefix + ".start", String.valueOf(start));
        upsert(type.keyPrefix + ".end", String.valueOf(end));
        log.info(
                "Range de {} atualizado para {}-{} ({} ativo(s) fora da nova faixa, preservados)",
                type.label, start, end, outOfRangeCount);
        return outOfRangeCount;
    }

    @Transactional(readOnly = true)
    public boolean isNpsEnabledGlobally() {
        return "true".equals(getRaw(NPS_ENABLED_KEY).orElse("true"));
    }

    @Transactional
    public void setNpsEnabledGlobally(boolean enabled) {
        upsert(NPS_ENABLED_KEY, String.valueOf(enabled));
        log.info("Interruptor global de NPS: {}", enabled ? "ativado" : "desativado");
    }

    private void validateRangeShape(RangeType type, int start, int end) {
        // Achado de segurança (security-reviewer): start<0 passava — em Java, -1000 % 1000 == 0,
        // então "start % 1000 == 0" por si só aceita blocos negativos (ex.: -1000 a -1). Isso
        // persistiria uma faixa inutilizável e travaria a alocação de ramal/fila/fluxo em
        // definitivo (auto-DoS) para quem tiver PERM_WRITE_callcenter.config.
        if (start < 1000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Início do range deve ser pelo menos 1000.");
        }
        if (start >= end) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Início do range deve ser menor que o fim.");
        }
        boolean isSingleThousandBlock = start % 1000 == 0 && end == start + 999;
        if (!isSingleThousandBlock) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Range de " + type.label + " deve ser um bloco de milhar completo (ex.: "
                            + "4000-4999) — é o único formato que o dialplan atual roteia sem "
                            + "edição manual do extensions.conf.");
        }
    }

    private void validateNoCollision(RangeType type, int start, int end) {
        int thousand = start / 1000;
        if (TELECOM_RESERVED_THOUSANDS.contains(thousand)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Faixa " + start + "-" + end + " colide com uma faixa já usada pelo módulo "
                            + "Telecom (URA legada, URAs ou softphones).");
        }
        for (RangeType other : RangeType.values()) {
            if (other == type) {
                continue;
            }
            ExtensionRange otherRange = getRange(other);
            if (otherRange.start() / 1000 == thousand) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Faixa " + start + "-" + end + " colide com a faixa de " + other.label
                                + " (" + otherRange.start() + "-" + otherRange.end() + ").");
            }
        }
    }

    private int countActiveOutsideRange(RangeType type, int start, int end) {
        long count =
                switch (type) {
                    case AGENT -> extensionRepository.countOutsideRange(start, end);
                    case QUEUE -> queueRepository.countOutsideRange(start, end);
                    case FLOW -> flowRepository.countOutsideRange(start, end);
                };
        return (int) count;
    }

    private int getInt(String key, int fallback) {
        return getRaw(key).map(Integer::parseInt).orElse(fallback);
    }

    private Optional<String> getRaw(String key) {
        return settingRepository.findBySettingKey(key).map(CcSetting::getSettingValue);
    }

    private void upsert(String key, String value) {
        CcSetting setting =
                settingRepository
                        .findBySettingKey(key)
                        .orElseGet(() -> CcSetting.builder().settingKey(key).build());
        setting.setSettingValue(value);
        settingRepository.save(setting);
    }
}
