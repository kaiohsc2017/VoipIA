package com.asteriskia.domain.callcenter.kb;

import java.time.LocalDateTime;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterKbStatsController — taxa de contenção do bot no mês corrente (§7 do plano-mãe, §25.4
 * — "é a métrica que prova se o gasto se paga"). RBAC via {@code PERM_READ_callcenter.kb}, mesmo
 * matcher do restante de {@code /callcenter/kb/**}.
 */
@RestController
@RequestMapping("/api/v1/callcenter/kb/stats")
@RequiredArgsConstructor
public class CallCenterKbStatsController {

    private final CcKbAnswerLogRepository answerLogRepository;

    public record KbStatsView(long matched, long total, double containmentRate) {}

    @GetMapping
    public ResponseEntity<KbStatsView> currentMonth() {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        long total = answerLogRepository.countTotalBetween(monthStart, now);
        long matched = answerLogRepository.countMatchedBetween(monthStart, now);
        double rate = total == 0 ? 0.0 : (double) matched / total;
        return ResponseEntity.ok(new KbStatsView(matched, total, rate));
    }
}
