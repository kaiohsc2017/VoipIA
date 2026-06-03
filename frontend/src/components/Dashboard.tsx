import { useEffect, useState, useCallback } from 'react';
import {
  AreaChart, Area, BarChart, Bar, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import api from '../api/client';
import { connectWebSocket, subscribe } from '../api/websocket';
import type { CallRecord, TestResult, AlertCall, PageResponse } from '../api/types';

// ─── Constants ─────────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  SUCESSO: '#68d391', FALHA: '#fc8181', OCUPADO: '#f6ad55',
  SEM_RESPOSTA: '#94a3b8', TIMEOUT: '#9f7aea', INVALIDO: '#fc8181',
  INDISPONIVEL: '#a0aec0', RECUSADO: '#fc8181',
};

const PIE_COLORS = ['#68d391', '#fc8181', '#f6ad55', '#9f7aea', '#94a3b8'];

function fmt(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

function fmtHour(iso: string) {
  return new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
}

// ─── Dashboard ─────────────────────────────────────────────────────────────────

export default function Dashboard() {
  const [calls, setCalls]     = useState<CallRecord[]>([]);
  const [results, setResults] = useState<TestResult[]>([]);
  const [alerts, setAlerts]   = useState<AlertCall[]>([]);
  const [loading, setLoading] = useState(true);
  const [wsStatus, setWsStatus] = useState<'connecting' | 'live' | 'offline'>('connecting');

  const loadData = useCallback(async () => {
    try {
      const [c, r, a] = await Promise.all([
        api.get<PageResponse<CallRecord>>('/calls?page=0&size=20'),
        api.get<PageResponse<TestResult>>('/test-results?page=0&size=100'),
        api.get<PageResponse<AlertCall>>('/alert-calls?page=0&size=20'),
      ]);
      setCalls(c.data.content ?? []);
      setResults(r.data.content ?? []);
      setAlerts(a.data.content ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();

    // WebSocket — tempo real
    const ws = connectWebSocket(() => setWsStatus('live'));
    ws.onDisconnect = () => setWsStatus('offline');

    const unsubCalls = subscribe<CallRecord>('/topic/calls', (newCall) => {
      setCalls(prev => [newCall, ...prev].slice(0, 20));
    });
    const unsubResults = subscribe<TestResult>('/topic/test-results', (newResult) => {
      setResults(prev => [newResult, ...prev].slice(0, 100));
    });

    return () => { unsubCalls(); unsubResults(); };
  }, [loadData]);

  // ─── KPIs ────────────────────────────────────────────────────────────────────
  const today = new Date().toDateString();
  const callsToday   = calls.filter(c => new Date(c.callDate).toDateString() === today).length;
  const resultsToday = results.filter(r => new Date(r.executedAt).toDateString() === today);
  const successToday = resultsToday.filter(r => r.status === 'SUCESSO').length;
  const successRate  = resultsToday.length > 0
    ? Math.round((successToday / resultsToday.length) * 100) : 0;
  const activeAlerts = alerts.filter(a => a.callStatus === 'PENDENTE').length;

  // ─── Chart data — área por hora ────────────────────────────────────────────
  const hourlyMap: Record<string, { hora: string; SUCESSO: number; FALHA: number; OUTROS: number }> = {};
  results.slice(0, 100).forEach(r => {
    const key = fmtHour(r.executedAt);
    if (!hourlyMap[key]) hourlyMap[key] = { hora: key, SUCESSO: 0, FALHA: 0, OUTROS: 0 };
    if (r.status === 'SUCESSO') hourlyMap[key].SUCESSO++;
    else if (r.status === 'FALHA') hourlyMap[key].FALHA++;
    else hourlyMap[key].OUTROS++;
  });
  const areaData = Object.values(hourlyMap).slice(-12);

  // ─── Pie chart data ─────────────────────────────────────────────────────────
  const statusCounts: Record<string, number> = {};
  results.forEach(r => { statusCounts[r.status] = (statusCounts[r.status] ?? 0) + 1; });
  const pieData = Object.entries(statusCounts)
    .map(([name, value]) => ({ name, value }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 5);

  // ─── Bar chart — chamadas por cliente ──────────────────────────────────────
  const clientMap: Record<string, number> = {};
  calls.forEach(c => {
    const k = c.clientName || 'Desconhecido';
    clientMap[k] = (clientMap[k] ?? 0) + 1;
  });
  const barData = Object.entries(clientMap)
    .map(([name, total]) => ({ name: name.length > 12 ? name.slice(0, 11) + '…' : name, total }))
    .sort((a, b) => b.total - a.total)
    .slice(0, 6);

  // ─── Custom Tooltip ─────────────────────────────────────────────────────────
  const CustomTooltip = ({ active, payload, label }: any) => {
    if (!active || !payload?.length) return null;
    return (
      <div style={{
        background: 'rgba(15,23,42,0.95)', border: '1px solid rgba(148,163,184,0.15)',
        borderRadius: 10, padding: '10px 14px', fontSize: '0.82rem',
      }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: 6 }}>{label}</p>
        {payload.map((p: any) => (
          <p key={p.name} style={{ color: p.color, margin: '2px 0' }}>
            {p.name}: <strong>{p.value}</strong>
          </p>
        ))}
      </div>
    );
  };

  if (loading) return (
    <div className="loading-state"><div className="spinner" />Carregando dashboard…</div>
  );

  return (
    <>
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <h1>📊 Dashboard</h1>
          <span style={{
            fontSize: '0.72rem', fontWeight: 600, letterSpacing: 1,
            padding: '3px 10px', borderRadius: 20,
            background: wsStatus === 'live' ? 'rgba(104,211,145,0.15)' : 'rgba(252,129,129,0.15)',
            color: wsStatus === 'live' ? '#68d391' : '#fc8181',
            border: `1px solid ${wsStatus === 'live' ? '#68d39140' : '#fc818140'}`,
          }}>
            {wsStatus === 'live' ? '⬤ LIVE' : wsStatus === 'connecting' ? '⬤ Conectando…' : '⬤ Offline'}
          </span>
        </div>
        <p>Visão geral do sistema AsteriskIA em tempo real</p>
      </div>

      <div className="page-body">

        {/* ── KPI Cards ─────────────────────────────────────────────────── */}
        <div className="kpi-grid">
          <KpiCard icon="🎫" value={callsToday} label="Chamadas Hoje"
            badge={callsToday > 0 ? `+${callsToday} hoje` : 'sem chamadas'}
            badgeClass={callsToday > 0 ? 'info' : 'gray'} />
          <KpiCard icon="📞" value={resultsToday.length} label="Testes Hoje"
            badge={`${successToday} sucessos`} badgeClass="success" />
          <KpiCard icon="✅" value={`${successRate}%`} label="Taxa de Sucesso"
            badge={successRate >= 80 ? '🟢 Saudável' : successRate >= 60 ? '🟡 Atenção' : '🔴 Crítico'}
            badgeClass={successRate >= 80 ? 'success' : successRate >= 60 ? 'warning' : 'danger'} />
          <KpiCard icon="🚨" value={activeAlerts} label="Alertas Ativos"
            badge={activeAlerts === 0 ? 'Nenhum' : `${activeAlerts} pendente${activeAlerts > 1 ? 's' : ''}`}
            badgeClass={activeAlerts === 0 ? 'success' : 'danger'} />
        </div>

        {/* ── Area Chart — Resultados por Hora ──────────────────────────── */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">📈 Testes de Conectividade — Linha do Tempo</span>
            <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              Últimos {results.length} resultados
            </span>
          </div>
          <div className="card-body" style={{ padding: '8px 16px 20px' }}>
            {areaData.length === 0 ? (
              <EmptyChart msg="Nenhum dado de teste disponível" />
            ) : (
              <ResponsiveContainer width="100%" height={220}>
                <AreaChart data={areaData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <defs>
                    <linearGradient id="gSuccess" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#68d391" stopOpacity={0.3} />
                      <stop offset="95%" stopColor="#68d391" stopOpacity={0.02} />
                    </linearGradient>
                    <linearGradient id="gFail" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#fc8181" stopOpacity={0.3} />
                      <stop offset="95%" stopColor="#fc8181" stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.08)" />
                  <XAxis dataKey="hora" tick={{ fill: '#64748b', fontSize: 11 }} />
                  <YAxis tick={{ fill: '#64748b', fontSize: 11 }} allowDecimals={false} />
                  <Tooltip content={<CustomTooltip />} />
                  <Legend wrapperStyle={{ fontSize: '0.82rem', color: '#94a3b8' }} />
                  <Area type="monotone" dataKey="SUCESSO" stroke="#68d391" fill="url(#gSuccess)" strokeWidth={2} />
                  <Area type="monotone" dataKey="FALHA"   stroke="#fc8181" fill="url(#gFail)"    strokeWidth={2} />
                  <Area type="monotone" dataKey="OUTROS"  stroke="#9f7aea" fill="none"            strokeWidth={1.5} strokeDasharray="4 2" />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>

        {/* ── Charts Row ────────────────────────────────────────────────── */}
        <div className="stats-grid">

          {/* Pie Chart — Distribuição de Status */}
          <div className="card">
            <div className="card-header">
              <span className="card-title">🍩 Distribuição de Status</span>
            </div>
            <div className="card-body" style={{ padding: '8px 16px 20px' }}>
              {pieData.length === 0 ? <EmptyChart msg="Sem dados de status" /> : (
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie data={pieData} cx="50%" cy="50%" innerRadius={52} outerRadius={80}
                      dataKey="value" nameKey="name"
                      paddingAngle={3}
                      // eslint-disable-next-line @typescript-eslint/no-explicit-any
                      label={({ name, percent }: any) =>
                        percent != null ? `${name ?? ''} ${((percent as number) * 100).toFixed(0)}%` : (name ?? '')
                      }
                      labelLine={false}
                    >
                      {pieData.map((_, i) => (
                        <Cell key={i} fill={STATUS_COLORS[pieData[i].name] ?? PIE_COLORS[i % PIE_COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip formatter={(v: any, n: any) => [v, n]} />
                  </PieChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>

          {/* Bar Chart — Chamadas por Cliente */}
          <div className="card">
            <div className="card-header">
              <span className="card-title">📊 Chamadas por Cliente</span>
            </div>
            <div className="card-body" style={{ padding: '8px 16px 20px' }}>
              {barData.length === 0 ? <EmptyChart msg="Nenhuma chamada registrada" /> : (
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={barData} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.08)" />
                    <XAxis dataKey="name" tick={{ fill: '#64748b', fontSize: 11 }} />
                    <YAxis tick={{ fill: '#64748b', fontSize: 11 }} allowDecimals={false} />
                    <Tooltip content={<CustomTooltip />} />
                    <Bar dataKey="total" fill="url(#barGrad)" radius={[4, 4, 0, 0]}>
                      <defs>
                        <linearGradient id="barGrad" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor="#7c3aed" />
                          <stop offset="100%" stopColor="#3b82f6" />
                        </linearGradient>
                      </defs>
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>
        </div>

        {/* ── Feed de Resultados em Tempo Real ──────────────────────────── */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">⚡ Feed em Tempo Real — Últimos Resultados</span>
            {wsStatus === 'live' && (
              <span style={{ fontSize: '0.76rem', color: '#68d391', animation: 'pulse 2s infinite' }}>
                ● Atualizando ao vivo
              </span>
            )}
          </div>
          <div className="card-body" style={{ padding: '0 20px 20px' }}>
            <div className="recent-results-list">
              {results.length === 0 ? (
                <p className="text-muted" style={{ textAlign: 'center', padding: 32 }}>
                  Aguardando primeiros resultados…
                </p>
              ) : results.slice(0, 12).map(r => (
                <div key={r.id} className="result-item">
                  <div className="result-status-dot"
                    style={{ background: STATUS_COLORS[r.status] ?? '#94a3b8' }} />
                  <span className="result-phone">Teste #{r.numberTest?.id ?? '?'}</span>
                  <span className="badge" style={{
                    background: `${STATUS_COLORS[r.status] ?? '#94a3b8'}20`,
                    color: STATUS_COLORS[r.status] ?? '#94a3b8',
                    border: `1px solid ${STATUS_COLORS[r.status] ?? '#94a3b8'}40`,
                  }}>
                    {r.status}
                  </span>
                  <span className="result-time">{fmt(r.executedAt)}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

      </div>
    </>
  );
}

// ─── Sub-components ────────────────────────────────────────────────────────────

function KpiCard({ icon, value, label, badge, badgeClass }: {
  icon: string; value: string | number;
  label: string; badge: string; badgeClass: string;
}) {
  return (
    <div className="kpi-card">
      <div className="kpi-icon">{icon}</div>
      <div className="kpi-value">{value}</div>
      <div className="kpi-label">{label}</div>
      <div className={`kpi-badge ${badgeClass}`}>{badge}</div>
    </div>
  );
}

function EmptyChart({ msg }: { msg: string }) {
  return (
    <p className="text-muted" style={{ textAlign: 'center', padding: '40px 0' }}>
      {msg}
    </p>
  );
}
