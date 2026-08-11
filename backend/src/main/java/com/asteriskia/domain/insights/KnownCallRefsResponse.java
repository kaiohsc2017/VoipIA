package com.asteriskia.domain.insights;

import java.util.List;

/** KnownCallRefsResponse — call_ref já conhecidos pelo backend + status atual de cada um.
 * O watcher Python usa o status pra decidir: 'done' → pular; 'pending'/'processing'/'error'
 * → (re)processar. Endpoint 100% interno (só consumido por insights/src/backend_client.py). */
public record KnownCallRefsResponse(List<CallStatusRef> calls) {}
