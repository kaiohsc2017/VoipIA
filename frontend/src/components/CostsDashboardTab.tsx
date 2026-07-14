import { useCallback, useEffect, useState } from 'react';
import api from '../api/client';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import type { ValueType } from 'recharts/types/component/DefaultTooltipContent';
import type { MonthlyCostSummary, Ura } from '../api/types';

function formatUsd(value: number) {
  return `US$ ${value.toFixed(2)}`;
}

function formatMonthLabel(month: string) {
  const [year, m] = month.split('-');
  const names = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun', 'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez'];
  return `${names[Number(m) - 1]}/${year.slice(2)}`;
}

/** Dashboard de Custos — evolução de gastos de IA mês a mês, com filtros por URA/período
 * (ModuloURA). Reusa recharts no mesmo padrão de DashboardTab. */
export function CostsDashboardTab({ uras }: { uras: Ura[] }) {
  const [summary, setSummary] = useState<MonthlyCostSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [uraFilter, setUraFilter] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');

  const load = useCallback(() => {
    setLoading(true);
    const params = new URLSearchParams();
    if (uraFilter) params.set('uraId', uraFilter);
    if (dateFrom) params.set('dateFrom', dateFrom);
    if (dateTo) params.set('dateTo', dateTo);
    api.get<MonthlyCostSummary[]>(`/calls/costs/summary?${params}`)
      .then(r => setSummary(r.data))
      .catch(err => {
        console.error('Erro ao carregar dashboard de custos:', err);
        setSummary([]);
      })
      .finally(() => setLoading(false));
  }, [uraFilter, dateFrom, dateTo]);

  useEffect(() => { load(); }, [load]);

  const totalCost = summary.reduce((sum, m) => sum + m.totalCostUsd, 0);
  const totalCalls = summary.reduce((sum, m) => sum + m.callCount, 0);
  const avgPerMonth = summary.length > 0 ? totalCost / summary.length : 0;
  const topMonth = summary.reduce<MonthlyCostSummary | null>(
    (top, m) => (!top || m.totalCostUsd > top.totalCostUsd ? m : top), null);

  const chartData = summary.map(m => ({
    month: formatMonthLabel(m.month),
    STT: m.sttCostUsd,
    LLM: m.llmCostUsd,
    TTS: m.ttsCostUsd,
  }));

  return (
    <div>
      <div className="flex gap-1" style={{ marginBottom: 16, flexWrap: 'wrap', alignItems: 'flex-end', gap: 12 }}>
        <div>
          <label className="form-label">URA</label>
          <select className="form-select" style={{ minWidth: 180 }} value={uraFilter} onChange={e => setUraFilter(e.target.value)}>
            <option value="">Todas as URAs</option>
            {uras.map(u => (
              <option key={u.id} value={u.id}>{u.name} (ramal {u.extension})</option>
            ))}
          </select>
        </div>
        <div>
          <label className="form-label">Data de</label>
          <input type="date" className="form-input" value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
        </div>
        <div>
          <label className="form-label">Data até</label>
          <input type="date" className="form-input" value={dateTo} onChange={e => setDateTo(e.target.value)} />
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
                📈 Evolução de gastos — mês a mês
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
                  <Bar dataKey="LLM" stackId="cost" fill="#007aff" />
                  <Bar dataKey="STT" stackId="cost" fill="#ff9f0a" />
                  <Bar dataKey="TTS" stackId="cost" fill="#34c759" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </>
      )}
    </div>
  );
}
