package com.asteriskia.domain.insights;

/** CallStatusRef — call_ref + status atual, usado por GET /api/v1/internal/insights/known-refs
 * para o watcher Python decidir se pula (done) ou (re)processa (pending/processing/error). */
public record CallStatusRef(String callRef, String status) {}
