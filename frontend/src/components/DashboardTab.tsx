import { useEffect, useState, useCallback, useRef } from 'react';
import { subscribe } from '../api/websocket';
import api from '../api/client';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import type { RankingDrillDownFilters } from './RankingTab';

interface TimePoint { date: string; total: number; jiraOpened: number; avgDuration: number; }

interface DashboardQuery { period: 'week' | 'month'; dateFrom: string; dateTo: string; }

export function DashboardTab({ onDrillDown }: { onDrillDown: (filters: RankingDrillDownFilters) => void }) {
  const [series, setSeries] = useState<TimePoint[]>([]);
  const [period, setPeriod] = useState<'week' | 'month'>('week');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [loading, setLoading] = useState(true);

  const hasCustomRange = !!(customFrom && customTo);

  const load = useCallback((q: DashboardQuery) => {
    setLoading(true);
    const params = (q.dateFrom && q.dateTo)
      ? new URLSearchParams({ dateFrom: q.dateFrom, dateTo: q.dateTo })
      : new URLSearchParams({ period: q.period });
    api.get<TimePoint[]>(`/stats/calls/timeseries?${params}`)
      .then(r => setSeries(r.data))
      .finally(() => setLoading(false));
  }, []);

  // Ref sempre atualizado com a consulta atual — evita stale closure no callback do WebSocket
  const queryRef = useRef<DashboardQuery>({ period, dateFrom: customFrom, dateTo: customTo });
  useEffect(() => { queryRef.current = { period, dateFrom: customFrom, dateTo: customTo }; }, [period, customFrom, customTo]);

  // Carrega na montagem e se inscreve no WebSocket para atualizar em tempo real
  useEffect(() => {
    load({ period: 'week', dateFrom: '', dateTo: '' });
    const unsub = subscribe('/topic/calls', () => {
      // Nova chamada registrada — recarrega o gráfico sem trocar o filtro atual
      load(queryRef.current);
    });
    return () => unsub?.();
    // Assinatura do WebSocket deve ocorrer só no mount — recriar a cada troca de
    // filtro reconectaria à toa. queryRef acima resolve o stale closure de `load`.
  }, []);  // eslint-disable-line react-hooks/exhaustive-deps

  const selectPeriod = (p: 'week' | 'month') => {
    setPeriod(p); setCustomFrom(''); setCustomTo('');
    load({ period: p, dateFrom: '', dateTo: '' });
  };
  const applyCustomRange = () => {
    if (!customFrom || !customTo) return;
    load({ period, dateFrom: customFrom, dateTo: customTo });
  };

  const formatDateLocal = (d: string) => {
    if (!d) return '';
    const dt = new Date(d);
    return `${String(dt.getDate()).padStart(2,'0')}/${String(dt.getMonth()+1).padStart(2,'0')}`;
  };

  const chartData = series.map(p => ({
    ...p,
    rawDate: p.date,
    date: formatDateLocal(p.date),
    Chamadas: p.total,
    'Jira Abertas': p.jiraOpened,
  }));

  const drillDownToDay = (entry: unknown) => {
    const item = entry as { rawDate?: string; payload?: { rawDate?: string } };
    const rawDate = item.payload?.rawDate ?? item.rawDate;
    if (rawDate) onDrillDown({ dateFrom: rawDate, dateTo: rawDate });
  };

  return (
    <div>
      <div className="flex gap-1" style={{ marginBottom: 16, flexWrap: 'wrap', alignItems: 'center' }}>
        {(['week', 'month'] as const).map(p => (
          <button key={p}
            className={`btn btn-sm ${period === p && !hasCustomRange ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => selectPeriod(p)}>
            {p === 'week' ? 'Últimos 7 dias' : 'Últimos 30 dias'}
          </button>
        ))}
        <span style={{ color: 'var(--text-muted)', fontSize: '.8rem', margin: '0 4px' }}>ou período customizado:</span>
        <input type="date" className="form-input" style={{ maxWidth: 150 }} value={customFrom}
          onChange={e => setCustomFrom(e.target.value)} />
        <input type="date" className="form-input" style={{ maxWidth: 150 }} value={customTo}
          onChange={e => setCustomTo(e.target.value)} />
        <button className={`btn btn-sm ${hasCustomRange ? 'btn-primary' : 'btn-ghost'}`}
          onClick={applyCustomRange} disabled={!customFrom || !customTo}>
          Aplicar
        </button>
      </div>
      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando gráfico…</div>
      ) : series.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
          Sem chamadas no período selecionado
        </div>
      ) : (
        <div className="stat-card" style={{ padding: 20 }}>
          <h3 style={{ marginBottom: 16, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
            📊 Chamadas URA por dia
          </h3>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={chartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
              <XAxis dataKey="date" tick={{ fill: '#94a3b8', fontSize: 12 }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} allowDecimals={false} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: '1px solid rgba(148,163,184,0.15)', borderRadius: 8 }}
                labelStyle={{ color: '#e2e8f0' }}
              />
              <Legend wrapperStyle={{ fontSize: 12, color: '#94a3b8' }} />
              <Bar dataKey="Chamadas" fill="#007aff" radius={[4,4,0,0]} cursor="pointer" onClick={drillDownToDay} />
              <Bar dataKey="Jira Abertas" fill="#3b82f6" radius={[4,4,0,0]} cursor="pointer" onClick={drillDownToDay} />
            </BarChart>
          </ResponsiveContainer>
          <div style={{ marginTop: 8, fontSize: '.75rem', color: 'var(--text-muted)' }}>
            Clique numa barra para ver as chamadas daquele dia
          </div>
        </div>
      )}
    </div>
  );
}
