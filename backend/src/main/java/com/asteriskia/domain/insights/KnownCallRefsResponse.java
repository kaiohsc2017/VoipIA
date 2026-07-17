package com.asteriskia.domain.insights;

import java.util.List;

/** KnownCallRefsResponse — call_ref já conhecidos pelo backend (dedupe do watcher em /opt/audio). */
public record KnownCallRefsResponse(List<String> callRefs) {}
