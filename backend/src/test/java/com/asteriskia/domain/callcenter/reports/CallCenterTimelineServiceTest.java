package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * A query SQL desta fatia (Fase 9c.3) usa sintaxe exclusiva do PostgreSQL (regexp_replace com
 * flag 'g', left(), substring(x from n for m)) — os testes deste projeto rodam contra H2
 * (ver src/test/resources/application*.properties), que não suporta essa sintaxe. A tradução da
 * normalização para SQL foi validada manualmente em produção via BEGIN/ROLLBACK antes do deploy
 * (mesma disciplina já usada para as migrations de particionamento V71/V72) — este teste cobre só
 * a validação de input que roda antes de qualquer acesso a banco.
 */
class CallCenterTimelineServiceTest {

    @Test
    void timeline_rejectsContactThatNormalizesToNull() {
        CallCenterTimelineService service = new CallCenterTimelineService();
        assertThatThrownBy(() -> service.timeline("   ", LocalDate.now().minusDays(1), LocalDate.now(),
                PageRequest.of(0, 20)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Contato inválido");
    }
}
