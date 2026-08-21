package com.asteriskia.domain.callcenter.maintenance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CcPartitionMaintenanceScheduler — achado de auditoria 2026-08-20 (MEDIUM): as migrations
 * V71/V72 particionaram {@code cc_interaction_events}/{@code cc_chat_messages}/
 * {@code cc_flow_execution_steps} por mês, mas só até 2027-12, sem nenhum job que estenda o
 * horizonte depois disso — o gap já estava documentado no comentário da própria V71 como
 * aceito por ora. Sem partição futura, o INSERT continua funcionando (cai na partição
 * {@code _default} de cada tabela), só perde o pruning — nunca falha, então este job é sobre
 * manter o benefício de performance, não sobre evitar erro.
 *
 * <p>Mirror de {@link com.asteriskia.domain.ai.AiModelPricingSyncScheduler}: roda 1x/dia, método
 * público reaproveitável sob demanda, nunca lança para o chamador em caso de falha parcial de
 * uma tabela (loga e segue para as demais).
 *
 * <p>Nomeação da partição idêntica à usada nas migrations (
 * {@code <tabela>_<YYYY_MM>}) — verificada via {@code to_regclass} (não tenta criar de novo uma
 * partição já existente, {@code CREATE TABLE IF NOT EXISTS} como segunda camada de defesa).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CcPartitionMaintenanceScheduler {

    /** Quantos meses à frente do mês corrente o horizonte de partições deve sempre cobrir. */
    private static final int MONTHS_AHEAD = 6;

    private static final DateTimeFormatter SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyy_MM");

    /** Tabelas particionadas por mês mantidas por este job (V71/V72). */
    private static final String[] PARTITIONED_TABLES = {
        "cc_interaction_events", "cc_chat_messages", "cc_flow_execution_steps"
    };

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "${app.callcenter.partition-maintenance-cron:0 15 3 * * ?}")
    public void scheduledMaintenance() {
        run();
    }

    /** Executa a verificação/criação de partições futuras para todas as tabelas mantidas.
     * Público e síncrono para ser reusado sob demanda (ex: endpoint administrativo futuro). */
    public void run() {
        YearMonth currentMonth = YearMonth.now();
        for (String table : PARTITIONED_TABLES) {
            try {
                ensureFuturePartitions(table, currentMonth);
            } catch (Exception e) {
                log.error(
                        "Falha ao garantir partições futuras de {}: {}",
                        table,
                        e.getClass().getSimpleName());
            }
        }
    }

    private void ensureFuturePartitions(String table, YearMonth currentMonth) {
        for (int offset = 0; offset <= MONTHS_AHEAD; offset++) {
            YearMonth month = currentMonth.plusMonths(offset);
            createPartitionIfMissing(table, month);
        }
    }

    private void createPartitionIfMissing(String table, YearMonth month) {
        String partitionName = table + "_" + month.format(SUFFIX_FORMAT);
        Boolean exists =
                jdbcTemplate.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + partitionName);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }

        LocalDate rangeStart = month.atDay(1);
        LocalDate rangeEnd = month.plusMonths(1).atDay(1);
        jdbcTemplate.execute(
                String.format(
                        "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                        partitionName, table, rangeStart, rangeEnd));
        log.info(
                "Partição {} criada para {} (intervalo [{}, {}))",
                partitionName,
                table,
                rangeStart,
                rangeEnd);
    }
}
