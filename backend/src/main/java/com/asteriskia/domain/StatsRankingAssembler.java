package com.asteriskia.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StatsRankingAssembler — conversão de linhas de agregação JPA (Object[]) em mapas prontos para
 * serialização JSON, extraído de StatsController (fase 8 da refatoração). Puramente mecânico, sem
 * acesso a repositório — todo o dado já vem calculado do banco.
 */
public final class StatsRankingAssembler {

    private StatsRankingAssembler() {}

    public static List<Map<String, Object>> toRankingList(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row[0]);
            item.put("total", ((Number) row[1]).longValue());
            result.add(item);
        }
        return result;
    }

    public static List<Map<String, Object>> toAvgDurationList(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row[0]);
            item.put("avgDurationSecs", Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0);
            result.add(item);
        }
        return result;
    }

    public static long sumTotal(List<Map<String, Object>> items) {
        return items.stream().mapToLong(i -> ((Number) i.get("total")).longValue()).sum();
    }

    public static double avgOfAvgDurations(List<Map<String, Object>> items) {
        return items.stream()
                .mapToDouble(i -> ((Number) i.get("avgDurationSecs")).doubleValue())
                .average()
                .orElse(0.0);
    }
}
