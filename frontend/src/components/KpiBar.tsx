import { useEffect, useState, useCallback, useRef } from 'react';
import { subscribe } from '../api/websocket';
import api from '../api/client';

interface CallStats {
  totalCalls: number;
  callsWithJira: number;
  jiraSuccessRatePct: number;
  avgDurationSecs: number;
}

export function KpiBar() {
  const [stats, setStats] = useState<CallStats | null>(null);
  const [period, setPeriod] = useState<'today' | 'week' | 'month'>('today');

  const load = useCallback((p: typeof period) => {
    api.get<CallStats>(`/stats/calls?period=${p}`)
      .then(r => setStats(r.data))
      .catch(err => console.error('Erro ao carregar estatísticas do KPI:', err));
  }, []);

  // Ref sempre atualizado com o período atual — evita stale closure no callback do WebSocket
  const periodRef = useRef(period);
  useEffect(() => { periodRef.current = period; }, [period]);

  useEffect(() => {
    load('today');
    const unsub = subscribe('/topic/calls', () => load(periodRef.current));
    return () => unsub?.();
    // Assinatura do WebSocket deve ocorrer só no mount — recriar a cada troca de
    // período reconectaria à toa. periodRef acima resolve o stale closure de `load`.
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (!stats) return null;

  const avgMin = stats.avgDurationSecs > 0
    ? `${Math.floor(stats.avgDurationSecs / 60)}m ${stats.avgDurationSecs % 60}s`
    : '—';

  return (
    <div style={{ marginBottom: 16 }}>
      <div className="flex gap-1" style={{ marginBottom: 10 }}>
        {(['today', 'week', 'month'] as const).map(p => (
          <button key={p} className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => { setPeriod(p); load(p); }}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
          </button>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 10 }}>
        {[
          { label: 'Chamadas URA',  value: stats.totalCalls,               color: '#007aff' },
          { label: 'Chamados Jira', value: stats.callsWithJira,            color: '#3b82f6' },
          { label: 'Taxa Jira',     value: `${stats.jiraSuccessRatePct}%`, color: '#34c759' },
          { label: 'Duração Média', value: avgMin,                         color: '#ff9f0a' },
        ].map(kpi => (
          <div key={kpi.label} className="stat-card" style={{ padding: '12px 16px' }}>
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>{kpi.label}</div>
            <div style={{ fontSize: '1.4rem', fontWeight: 700, color: kpi.color }}>{kpi.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
