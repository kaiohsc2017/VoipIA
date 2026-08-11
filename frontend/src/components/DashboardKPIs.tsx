import { useEffect, useState } from 'react';
import {
  BarChart, Bar, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import api from '../api/client';

interface ConnectivityStats {
  totalTestsToday: number;
  successesToday: number;
  failuresToday: number;
  totalTestsWeek: number;
  successesWeek: number;
  failuresWeek: number;
  successRatePct: number;
  failRatePct: number;
  completionRatePct: number;
  pendingPct: number;
  scheduledCount: number;
}

export function DashboardKPIs() {
  const [stats, setStats] = useState<ConnectivityStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState<'today' | 'week' | 'month'>('today');

  const load = (p: 'today' | 'week' | 'month') => {
    setLoading(true);
    api.get<ConnectivityStats>(`/stats/connectivity?period=${p}`)
      .then(r => setStats(r.data))
      .catch(err => console.error('Erro ao carregar KPIs de conectividade:', err))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load('today'); }, []);

  const handlePeriod = (p: typeof period) => { setPeriod(p); load(p); };

  if (loading) return <div className="loading-state"><div className="spinner" />Carregando KPIs…</div>;
  if (!stats) return null;

  const total = period === 'today' ? stats.totalTestsToday : stats.totalTestsWeek;
  const success = period === 'today' ? stats.successesToday : stats.successesWeek;
  const failures = period === 'today' ? stats.failuresToday : stats.failuresWeek;
  const pieData = [
    { name: 'Sucesso', value: success },
    { name: 'Falha/Outro', value: Math.max(0, total - success) },
  ];
  const barData = [
    { name: 'Realizados', value: total },
    { name: 'Agendados', value: stats.scheduledCount },
  ];

  return (
    <div>
      {/* Filtros */}
      <div className="flex gap-1" style={{ marginBottom: 20 }}>
        {(['today', 'week', 'month'] as const).map(p => (
          <button key={p} className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => handlePeriod(p)}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
          </button>
        ))}
      </div>

      {/* KPI cards — os 7 solicitados */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(170px, 1fr))', gap: 12, marginBottom: 24 }}>
        {[
          { label: 'Testes Realizados', value: total, color: '#007aff' },
          { label: 'Testes Agendados', value: stats.scheduledCount, color: '#3b82f6' },
          { label: 'Sucessos', value: success, color: '#34c759' },
          { label: 'Falhas', value: failures, color: '#ff6b6b' },
          { label: 'Taxa de Sucesso', value: `${stats.successRatePct}%`, color: '#34c759' },
          { label: 'Taxa de Falha', value: `${stats.failRatePct}%`, color: '#ff6b6b' },
          { label: '% Realizado', value: `${stats.completionRatePct}%`, color: '#ff9f0a' },
        ].map(kpi => (
          <div key={kpi.label} className="stat-card" style={{ padding: '16px 20px' }}>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 4 }}>{kpi.label}</div>
            <div style={{ fontSize: '1.6rem', fontWeight: 700, color: kpi.color }}>{kpi.value}</div>
          </div>
        ))}
      </div>

      {/* Gráficos */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        <div className="stat-card" style={{ padding: 16 }}>
          <h3 style={{ fontSize: '0.9rem', marginBottom: 12, color: 'var(--text-muted)' }}>Sucesso × Falha</h3>
          <ResponsiveContainer width="100%" height={200}>
            <PieChart>
              <Pie data={pieData} cx="50%" cy="50%" outerRadius={70} dataKey="value" label={({ name, percent }) => percent != null ? `${name} ${(percent * 100).toFixed(0)}%` : name}>
                <Cell fill="#34c759" />
                <Cell fill="#ff6b6b" />
              </Pie>
              <Tooltip />
            </PieChart>
          </ResponsiveContainer>
        </div>
        <div className="stat-card" style={{ padding: 16 }}>
          <h3 style={{ fontSize: '0.9rem', marginBottom: 12, color: 'var(--text-muted)' }}>Realizados × Agendados</h3>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={barData}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
              <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid rgba(255,255,255,0.1)' }} />
              <Bar dataKey="value" fill="#007aff" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
}
