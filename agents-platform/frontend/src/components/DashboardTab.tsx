import { useEffect, useState } from 'react';
import { CheckCircle2, AlertTriangle, Bell, Bot } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import api, { getErrorMessage } from '../api/client';
import { StatusBadge } from './StatusBadge';
import type { DashboardSummary, PeriodRow } from '../api/types';

type Period = 'day' | 'week' | 'month';

export function DashboardTab() {
  const [data, setData] = useState<DashboardSummary | null>(null);
  const [pdata, setPdata] = useState<PeriodRow[]>([]);
  const [period, setPeriod] = useState<Period>('day');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = (p: Period = period) => {
    setLoading(true);
    setError('');
    Promise.all([
      api.get<DashboardSummary>('/api/executions/dashboard/summary'),
      api.get<PeriodRow[]>(`/api/executions/dashboard/period?period=${p}`),
    ])
      .then(([summaryRes, periodRes]) => {
        setData(summaryRes.data);
        setPdata(Array.isArray(periodRes.data) ? periodRes.data : []);
      })
      .catch(err => setError(getErrorMessage(err, 'Erro ao carregar dados do dashboard.')))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const changePeriod = (p: Period) => { setPeriod(p); load(p); };

  if (loading) {
    return (
      <>
        <div className="page-header"><h1>Dashboard</h1></div>
        <div className="page-body"><div className="loading-state"><div className="spinner" />Carregando...</div></div>
      </>
    );
  }

  if (!data) {
    return (
      <>
        <div className="page-header"><h1>Dashboard</h1></div>
        <div className="page-body">
          <div className="card"><div className="card-body table-empty">
            <p style={{ color: 'var(--clr-danger)', marginBottom: 8 }}>{error || 'Erro ao carregar dados do dashboard.'}</p>
            <button className="btn btn-ghost" onClick={() => load()}>Tentar novamente</button>
          </div></div>
        </div>
      </>
    );
  }

  const availabilityData = pdata.map(r => ({
    name: r.agent_name,
    pct: r.total > 0 ? Math.round((r.ok / r.total) * 100) : 0,
  }));

  return (
    <>
      <div className="page-header"><h1>Dashboard</h1><p>Visão geral da Plataforma de Agentes</p></div>
      <div className="page-body">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 24, marginBottom: 24 }}>
          <KpiCard icon={Bot} value={data.active_agents} label="Agentes ativos" badge="Ativos" badgeClass="info" />
          <KpiCard icon={CheckCircle2} value={data.executions_24h.ok} label="Execuções OK (24h)" badge="OK" badgeClass="success" />
          <KpiCard icon={AlertTriangle} value={data.executions_24h.errors} label="Erros (24h)" badge="Erros" badgeClass="danger" />
          <KpiCard icon={Bell} value={data.alerts_24h} label="Alertas (24h)" badge="Alertas" badgeClass="warning" />
        </div>

        {availabilityData.length > 0 && (
          <div className="card" style={{ marginBottom: 24 }}>
            <div className="card-header"><span className="card-title">Disponibilidade por agente</span></div>
            <div className="card-body">
              <ResponsiveContainer width="100%" height={Math.max(120, availabilityData.length * 40)}>
                <BarChart data={availabilityData} layout="vertical" margin={{ left: 24 }}>
                  <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                  <XAxis type="number" domain={[0, 100]} tickFormatter={v => `${v}%`} />
                  <YAxis type="category" dataKey="name" width={140} />
                  <Tooltip formatter={(v) => `${v}%`} />
                  <Bar dataKey="pct" radius={[0, 4, 4, 0]}>
                    {availabilityData.map((d, i) => (
                      <Cell key={i} fill={d.pct >= 95 ? 'var(--clr-success)' : d.pct >= 80 ? 'var(--clr-warning)' : 'var(--clr-danger)'} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        )}

        <div className="card" style={{ marginBottom: 24 }}>
          <div className="card-header"><span className="card-title">Execuções recentes</span></div>
          <div className="table-wrapper">
            <table>
              <thead>
                <tr><th>Agente</th><th>Status</th><th>Verificações</th><th>Duração</th><th>Início</th></tr>
              </thead>
              <tbody>
                {data.recent_executions.length === 0 ? (
                  <tr><td colSpan={5} className="table-empty">Nenhuma execução registrada</td></tr>
                ) : data.recent_executions.map(e => (
                  <tr key={e.id}>
                    <td style={{ fontWeight: 500 }}>{e.agent_name}</td>
                    <td><StatusBadge status={e.status} /></td>
                    <td>
                      <span style={{ color: 'var(--clr-success)', fontWeight: 500 }}>{e.passed_checks ?? 0}</span>
                      <span className="text-muted"> / </span>{e.total_checks ?? 0}
                      {!!e.failed_checks && (
                        <span style={{ color: 'var(--clr-danger)', fontSize: 11, marginLeft: 4 }}>
                          ({e.failed_checks} falha{e.failed_checks > 1 ? 's' : ''})
                        </span>
                      )}
                    </td>
                    <td>{e.duration_s ? `${e.duration_s.toFixed(1)}s` : '—'}</td>
                    <td className="td-muted">{new Date(e.started_at).toLocaleString('pt-BR')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">Por período</span>
            <div className="flex gap-1">
              <button className={`btn btn-sm ${period === 'day' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => changePeriod('day')}>24h</button>
              <button className={`btn btn-sm ${period === 'week' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => changePeriod('week')}>7d</button>
              <button className={`btn btn-sm ${period === 'month' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => changePeriod('month')}>30d</button>
            </div>
          </div>
          <div className="table-wrapper">
            <table>
              <thead>
                <tr><th>Agente</th><th>Execuções</th><th>OK</th><th>Erros</th><th>Tempo médio</th><th>Falhas</th></tr>
              </thead>
              <tbody>
                {pdata.length === 0 ? (
                  <tr><td colSpan={6} className="table-empty">Sem dados no período</td></tr>
                ) : pdata.map((r, i) => (
                  <tr key={i}>
                    <td>{r.agent_name}</td>
                    <td>{r.total}</td>
                    <td style={{ color: 'var(--clr-success)', fontWeight: 500 }}>{r.ok}</td>
                    <td style={{ color: 'var(--clr-danger)', fontWeight: 500 }}>{r.errors}</td>
                    <td>{r.avg_duration ? `${Number(r.avg_duration).toFixed(1)}s` : '—'}</td>
                    <td>{r.failures ?? 0}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}

function KpiCard({ icon: Icon, value, label, badge, badgeClass }: {
  icon: typeof Bot; value: string | number; label: string; badge: string; badgeClass: string;
}) {
  return (
    <div className="kpi-card">
      <div className="kpi-card-top">
        <span className="kpi-label">{label}</span>
        <div className={`kpi-icon ${badgeClass}`}><Icon size={16} strokeWidth={1.75} /></div>
      </div>
      <div className="kpi-value">{value}</div>
      <div className={`kpi-badge ${badgeClass}`}>{badge}</div>
    </div>
  );
}
