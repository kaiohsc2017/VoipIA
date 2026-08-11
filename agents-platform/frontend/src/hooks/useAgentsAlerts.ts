import { useEffect, useState } from 'react';
import { telecomApi } from '../api/client';

/**
 * useAgentsAlerts — abre o WebSocket global de alertas (`/agents/ws/alerts`)
 * e conta mensagens de nível error/warning. Mesmo fluxo do app legado
 * (index.html:1319-1340): busca um token de streaming de vida curta (60s) no
 * backend Telecom antes de conectar — o JWT principal (8h) nunca vai na
 * query string do WS (evita vazar em logs de acesso/histórico do browser).
 * Sem reconexão automática — paridade com o comportamento anterior.
 */
export function useAgentsAlerts(enabled: boolean): number {
  const [alertCount, setAlertCount] = useState(0);

  useEffect(() => {
    if (!enabled) return;
    let ws: WebSocket | undefined;
    let cancelled = false;

    telecomApi.post<{ token: string }>('/auth/streaming-token')
      .then(({ data }) => {
        if (cancelled || !data.token) return;
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${proto}//${location.host}/agents/ws/alerts?token=${encodeURIComponent(data.token)}`);
        ws.onmessage = (e) => {
          try {
            const d = JSON.parse(e.data || '{}');
            if (d.level === 'error' || d.level === 'warning') {
              setAlertCount(n => n + 1);
            }
          } catch {
            // mensagem não-JSON (ex: ping de keepalive) — ignora
          }
        };
      })
      .catch(() => { /* sem alertas em tempo real se o streaming-token falhar */ });

    return () => {
      cancelled = true;
      try { ws?.close(); } catch { /* já fechado */ }
    };
  }, [enabled]);

  return alertCount;
}
