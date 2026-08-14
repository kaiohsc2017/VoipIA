package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDateTime;

/** ChatReportFilter — filtros do relatório analítico de chat (Fase 9c). Sem filtro de nota NPS
 * (pesquisa de satisfação hoje só liga a {@code cc_interactions}, nunca a {@code cc_chat_sessions}
 * — fora de escopo desta fase) nem de trecho de transcrição (o transcript de chat é só um arquivo
 * exportado ao encerrar a sessão, sem índice de busca full-text como o de voz). */
public record ChatReportFilter(
        LocalDateTime from,
        LocalDateTime to,
        Long queueId,
        Long agentId) {}
