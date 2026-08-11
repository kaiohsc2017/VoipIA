import { useCallback, useEffect, useState } from 'react';
import api from '../api/client';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import type { ValueType } from 'recharts/types/component/DefaultTooltipContent';
import type { FinanceiroDrillDownFilters, InsightMonthlyCostSummary } from '../api/types';

const MONTH_NAMES = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun', 'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez'];

function formatUsd(value: number) {
  return `US$ ${value.toFixed(2)}`;
}

function formatMonthLabel(month: string) {
  const [year, m] = month.split('-');
  return `${MONTH_NAMES[Number(m) - 1]}/${year.slice(2)}`;
}

interface InsightsCostsDashboardTabProps {
  onDrillDown: (filters: FinanceiroDrillDownFilters) => void;
  /** Endpoint a consumir — fluxo Verint (/insights/costs/summary) ou Análise Sob Demanda
   * (/insights/uploads/costs/summary), parametrizado pelo módulo Financeiro. */
  basePath: string;
}

/** Dashboard de Custos do módulo Financeiro (frentes Insights/Análise Sob Demanda) — mirror
 * exato de CostsDashboardTab.tsx (URA), sem filtro de URA (Insights não tem) — trocado por
 * filtro de atendente. Só STT+LLM, sem TTS. Gráfico fixo no ano corrente (Jan-Dez, tamanho
 * não cresce com o histórico) e com drill-down: clicar num mês leva para a aba "Custos IA"
 * já filtrada por aquele mês. */
export function InsightsCostsDashboardTab({ onDrillDown, basePath }: InsightsCostsDashboardTabProps) {
  const [summary, setSummary] = useState<InsightMonthlyCostSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [agentNameFilter, setAgentNameFilter] = useState('');

  const year = new Date().getFullYear();

  const load = useCallback(() => {
    setLoading(true);
    const params = new URLSearchParams({ dateFrom: `${year}-01-01`, dateTo: `${year}-12-31` });
    if (agentNameFilter) params.set('agentName', agentNameFilter);
    api.get<InsightMonthlyCostSummary[]>(`${basePath}?${params}`)
      .then(r => setSummary(r.data))
      .catch(err => {
        console.error('Erro ao carregar dashboard de custos:', err);
        setSummary([]);
      })
      .finally(() => setLoading(false));
  }, [agentNameFilter, year, basePath]);

  useEffect(() => { load(); }, [load]);

  const totalCost = summary.reduce((sum, m) => sum + m.totalCostUsd, 0);
  const totalCalls = summary.reduce((sum, m) => sum + m.callCount, 0);
  const avgPerMonth = summary.length > 0 ? totalCost / summary.length : 0;
  const topMonth = summary.reduce<InsightMonthlyCostSummary | null>(
    (top, m) => (!top || m.totalCostUsd > top.totalCostUsd ? m : top), null);

  const chartData = Array.from({ length: 12 }, (_, i) => {
    const rawMonth = `${year}-${String(i + 1).padStart(2, '0')}`;
    const found = summary.find(m => m.month === rawMonth);
    return {
      rawMonth,
      month: formatMonthLabel(rawMonth),
      STT: found?.sttCostUsd ?? 0,
      LLM: found?.llmCostUsd ?? 0,
    };
  });

  const drillDownToMonth = (entry: unknown) => {
    const item = entry as { rawMonth?: string; payload?: { rawMonth?: string } };
    const rawMonth = item.payload?.rawMonth ?? item.rawMonth;
    if (!rawMonth) return;
    const [y, m] = rawMonth.split('-').map(Number);
    const dateFrom = `${rawMonth}-01`;
    const lastDay = new Date(y, m, 0).getDate();
    const dateTo = `${rawMonth}-${String(lastDay).padStart(2, '0')}`;
    onDrillDown({ dateFrom, dateTo });
  };

  return (
    <div>
      <div className="flex gap-1" style={{ marginBottom: 16, flexWrap: 'wrap', alignItems: 'flex-end', gap: 12 }}>
        <div>
          <label className="form-label">Atendente</label>
          <input className="form-input" style={{ minWidth: 180 }} placeholder="ex: Rafael Matos" value={agentNameFilter} onChange={e => setAgentNameFilter(e.target.value)} />
        </div>
        <div>
          <label className="form-label">Ano</label>
          <input className="form-input" style={{ minWidth: 80 }} value={year} disabled />
        </div>
      </div>

      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando dashboard de custos…</div>
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 10, marginBottom: 20 }}>
            <div className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Custo total no período</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#007aff' }}>{formatUsd(totalCost)}</div>
            </div>
            <div className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Média mensal</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#3b82f6' }}>{formatUsd(avgPerMonth)}</div>
            </div>
            <div className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Mês de maior gasto</div>
              <div style={{ fontSize: '1rem', fontWeight: 700, color: '#ff9f0a' }}>
                {topMonth ? `${formatMonthLabel(topMonth.month)} — ${formatUsd(topMonth.totalCostUsd)}` : '—'}
              </div>
            </div>
            <div className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Chamadas com IA</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#34c759' }}>{totalCalls}</div>
            </div>
          </div>

          {summary.length === 0 ? (
            <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
              Sem custo de IA registrado no período selecionado
            </div>
          ) : (
            <div className="stat-card" style={{ padding: 20 }}>
              <h3 style={{ marginBottom: 16, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
                📈 Evolução de gastos — mês a mês ({year})
              </h3>
              <ResponsiveContainer width="100%" height={280}>
                <BarChart data={chartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
                  <XAxis dataKey="month" tick={{ fill: '#94a3b8', fontSize: 12 }} />
                  <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} tickFormatter={v => `US$${v}`} />
                  <Tooltip
                    contentStyle={{ background: '#1e293b', border: '1px solid rgba(148,163,184,0.15)', borderRadius: 8 }}
                    labelStyle={{ color: '#e2e8f0' }}
                    formatter={(value: ValueType | undefined) => typeof value === 'number' ? formatUsd(value) : String(value ?? '')}
                  />
                  <Legend wrapperStyle={{ fontSize: 12, color: '#94a3b8' }} />
                  <Bar dataKey="LLM" stackId="cost" fill="#007aff" cursor="pointer" onClick={drillDownToMonth} />
                  <Bar dataKey="STT" stackId="cost" fill="#ff9f0a" radius={[4, 4, 0, 0]} cursor="pointer" onClick={drillDownToMonth} />
                </BarChart>
              </ResponsiveContainer>
              <div style={{ marginTop: 8, fontSize: '.75rem', color: 'var(--text-muted)' }}>
                Clique num mês para ver os custos daquele período
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
