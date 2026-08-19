import { useEffect, useState, useCallback } from 'react';
import type { LucideIcon } from 'lucide-react';
import {
  Ticket, PhoneCall, Timer, Radio, DollarSign,
} from 'lucide-react';
import {
  BarChart, Bar, LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import type { TooltipContentProps } from 'recharts';
import type { ValueType, NameType } from 'recharts/types/component/DefaultTooltipContent';
import api from '../api/client';
import { connectWebSocket, subscribe } from '../api/websocket';
import { useAuthSession } from '../hooks/useAuthSession';
import type { CallRecord, PageResponse, MonthlyCostSummary, InsightMonthlyCostSummary } from '../api/types';

const COST_MONTH_NAMES = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun', 'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez'];

function fmt(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

function fmtCurrency(value: number): string {
  return value.toLocaleString('pt-BR', { style: 'currency', currency: 'USD' });
}

interface TrunkStatus {
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN';
  rttMs: number;
  checkedAt: string;
}

export default function Dashboard() {
  const [calls, setCalls]     = useState<CallRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [wsStatus, setWsStatus] = useState<'connecting' | 'live' | 'offline'>('connecting');
  const [trunkStatus, setTrunkStatus] = useState<TrunkStatus | null>(null);
  const [costSummary, setCostSummary] = useState<{
    ura: MonthlyCostSummary[]; insights: InsightMonthlyCostSummary[]; envios: InsightMonthlyCostSummary[];
  }>({ ura: [], insights: [], envios: [] });

  const { hasRead } = useAuthSession();
  const canReadUra = hasRead('financeiro.ura');
  const canReadInsights = hasRead('financeiro.insights');
  const canReadEnvios = hasRead('financeiro.envios');
  const showCosts = canReadUra || canReadInsights || canReadEnvios;

  const fetchTrunkStatus = useCallback(async () => {
    try {
      const res = await api.get<TrunkStatus>('/stats/trunk-status');
      setTrunkStatus(res.data);
    } catch {
      setTrunkStatus({ status: 'UNKNOWN', rttMs: -1, checkedAt: new Date().toISOString() });
    }
  }, []);

  const loadData = useCallback(async () => {
    try {
      const c = await api.get<PageResponse<CallRecord>>('/calls?page=0&size=50');
      setCalls(c.data.content ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadCosts = useCallback(async () => {
    const year = new Date().getFullYear();
    const params = `dateFrom=${year}-01-01&dateTo=${year}-12-31`;
    const [ura, insights, envios] = await Promise.all([
      canReadUra
        ? api.get<MonthlyCostSummary[]>(`/calls/costs/summary?${params}`).then(r => r.data).catch(() => [])
        : Promise.resolve([]),
      canReadInsights
        ? api.get<InsightMonthlyCostSummary[]>(`/insights/costs/summary?${params}`).then(r => r.data).catch(() => [])
        : Promise.resolve([]),
      canReadEnvios
        ? api.get<InsightMonthlyCostSummary[]>(`/insights/uploads/costs/summary?${params}`).then(r => r.data).catch(() => [])
        : Promise.resolve([]),
    ]);
    setCostSummary({ ura, insights, envios });
  }, [canReadUra, canReadInsights, canReadEnvios]);

  useEffect(() => {
    loadData();
    fetchTrunkStatus();
    if (showCosts) loadCosts();

    const ws = connectWebSocket(() => setWsStatus('live'));
    ws.onDisconnect = () => setWsStatus('offline');

    const trunkInterval = setInterval(fetchTrunkStatus, 60_000);

    const unsubCalls = subscribe<CallRecord>('/topic/calls', (newCall) => {
      setCalls(prev => [newCall, ...prev].slice(0, 50));
    });

    return () => { unsubCalls(); clearInterval(trunkInterval); };
  }, [loadData, fetchTrunkStatus, loadCosts, showCosts]);

  // ─── KPIs ──────────────────────────────────────────────────────────────────
  const today = new Date().toDateString();
  const callsToday   = calls.filter(c => new Date(c.callDate).toDateString() === today).length;
  const withTicket   = calls.filter(c => !!c.jiraIssueKey).length;
  const avgDuration  = calls.length > 0
    ? Math.round(calls.reduce((s, c) => s + (c.callDurationSecs ?? 0), 0) / calls.length) : 0;

  // Custo acumulado do mês atual
  const currentMonthIdx = new Date().getMonth();
  const currentYear = new Date().getFullYear();
  const currentMonthStr = `${currentYear}-${String(currentMonthIdx + 1).padStart(2, '0')}`;
  const currentMonthUra = costSummary.ura.find(s => s.month === currentMonthStr);
  const currentMonthIns = costSummary.insights.find(s => s.month === currentMonthStr);
  const currentMonthEnv = costSummary.envios.find(s => s.month === currentMonthStr);
  const totalCostThisMonth = (currentMonthUra?.totalCostUsd ?? 0)
    + (currentMonthIns?.totalCostUsd ?? 0)
    + (currentMonthEnv?.totalCostUsd ?? 0);

  // ─── Chart data ────────────────────────────────────────────────────────────
  const clientMap: Record<string, number> = {};
  calls.forEach(c => {
    const name = c.clientName ?? 'Desconhecido';
    clientMap[name] = (clientMap[name] ?? 0) + 1;
  });
  const barData = Object.entries(clientMap)
    .map(([name, total]) => ({ name, total }))
    .sort((a, b) => b.total - a.total).slice(0, 8);

  const costMonths = Array.from({ length: 12 }, (_, i) => {
    const mStr = `${currentYear}-${String(i + 1).padStart(2, '0')}`;
    const uraMonth = costSummary.ura.find(s => s.month === mStr);
    const insMonth = costSummary.insights.find(s => s.month === mStr);
    const envMonth = costSummary.envios.find(s => s.month === mStr);
    return {
      name: COST_MONTH_NAMES[i],
      URA: Number((uraMonth?.totalCostUsd ?? 0).toFixed(2)),
      Insights: Number((insMonth?.totalCostUsd ?? 0).toFixed(2)),
      Envios: Number((envMonth?.totalCostUsd ?? 0).toFixed(2)),
      Total: Number(((uraMonth?.totalCostUsd ?? 0) + (insMonth?.totalCostUsd ?? 0) + (envMonth?.totalCostUsd ?? 0)).toFixed(2)),
    };
  });

  const CustomTooltip = ({ active, payload, label }: Partial<TooltipContentProps<ValueType, NameType>>) => {
    if (!active || !payload?.length) return null;
    return (
      <div style={{
        background: 'rgba(15,23,42,0.95)', border: '1px solid rgba(148,163,184,0.15)',
        borderRadius: 10, padding: '10px 14px', fontSize: '0.82rem',
      }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: 6 }}>{label}</p>
        {payload.map((p) => (
          <p key={p.name} style={{ color: p.color, margin: '2px 0' }}>
            {p.name}: <strong>{typeof p.value === 'number' && String(p.name).includes('Cost') || String(p.name).includes('Total') || String(p.name).includes('URA') || String(p.name).includes('Insights') || String(p.name).includes('Envios') ? fmtCurrency(Number(p.value)) : p.value}</strong>
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
        <div className="page-title-group">
          <h1>Dashboard Principal</h1>
          <p className="page-subtitle">
            Visão geral da URA com Inteligência Artificial, atendimentos e custos de processamento
          </p>
        </div>
        <div className="header-actions">
          <span className={`badge ${wsStatus === 'live' ? 'badge-success' : 'badge-neutral'}`}>
            <span className="live-dot" />
            {wsStatus === 'live' ? 'WebSocket Ativo' : 'Reconectando…'}
          </span>
          <button className="btn btn-secondary" onClick={() => { setLoading(true); loadData(); fetchTrunkStatus(); if (showCosts) loadCosts(); }}>
            ↻ Atualizar
          </button>
        </div>
      </div>

      <div className="page-content">

        {/* ── KPIs ─────────────────────────────────────────────────────────── */}
        <div className="kpi-grid">
          <KpiCard
            icon={PhoneCall}
            value={callsToday}
            label="Chamadas URA Hoje"
            badge={`${calls.length} total`}
            badgeClass="badge-info"
          />
          <KpiCard
            icon={Ticket}
            value={withTicket}
            label="Chamadas com Ticket"
            badge={calls.length > 0 ? `${Math.round((withTicket / calls.length) * 100)}% das chamadas` : '0%'}
            badgeClass="badge-success"
          />
          <KpiCard
            icon={Timer}
            value={`${avgDuration}s`}
            label="Duração Média URA"
            badge="tempo de fala"
            badgeClass="badge-neutral"
          />
          {showCosts ? (
            <KpiCard
              icon={DollarSign}
              value={fmtCurrency(totalCostThisMonth)}
              label="Custo de IA no Mês"
              badge={COST_MONTH_NAMES[currentMonthIdx]}
              badgeClass="badge-warning"
            />
          ) : (
            <KpiCard
              icon={Radio}
              value={
                trunkStatus?.status === 'ONLINE'
                  ? `Online (${trunkStatus.rttMs >= 0 ? `${trunkStatus.rttMs}ms` : 'ok'})`
                  : trunkStatus?.status === 'OFFLINE'
                  ? 'Offline'
                  : 'Checando…'
              }
              label="Tronco SIP"
              badge={trunkStatus?.checkedAt ? fmt(trunkStatus.checkedAt) : 'Sem dados'}
              badgeClass={
                trunkStatus?.status === 'ONLINE'
                  ? (trunkStatus.rttMs > 200 ? 'badge-warning' : 'badge-success')
                  : trunkStatus?.status === 'OFFLINE'
                  ? 'badge-danger'
                  : 'badge-neutral'
              }
            />
          )}
        </div>

        {/* ── Gráficos de Custos e Clientes ───────────────────────────────── */}
        <div className="dashboard-grid-charts" style={{ display: 'grid', gridTemplateColumns: showCosts ? '2fr 1fr' : '1fr', gap: 20, marginBottom: 20 }}>
          {showCosts && (
            <div className="card">
              <div className="card-header">
                <span className="card-title">Evolução Mensal de Custos de IA ({currentYear})</span>
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>URA · Insights · Envios</span>
              </div>
              <div className="card-body" style={{ height: 260, padding: '10px 10px 0 0' }}>
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={costMonths} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                    <XAxis dataKey="name" tick={{ fill: '#64748b', fontSize: 11 }} />
                    <YAxis tick={{ fill: '#64748b', fontSize: 11 }} />
                    <Tooltip content={<CustomTooltip />} />
                    <Legend wrapperStyle={{ fontSize: '0.78rem', paddingTop: 8 }} />
                    {canReadUra && <Line type="monotone" dataKey="URA" stroke="#007aff" strokeWidth={2} dot={{ r: 3 }} />}
                    {canReadInsights && <Line type="monotone" dataKey="Insights" stroke="#34c759" strokeWidth={2} dot={{ r: 3 }} />}
                    {canReadEnvios && <Line type="monotone" dataKey="Envios" stroke="#ff9f0a" strokeWidth={2} dot={{ r: 3 }} />}
                    <Line type="monotone" dataKey="Total" stroke="#af52de" strokeWidth={2.5} strokeDasharray="4 4" dot={{ r: 4 }} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>
          )}

          <div className="card">
            <div className="card-header">
              <span className="card-title">Chamadas por Cliente</span>
            </div>
            <div className="card-body" style={{ height: 260, padding: '10px 10px 0 0' }}>
              {barData.length === 0 ? (
                <EmptyChart msg="Nenhuma chamada registrada no período" />
              ) : (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={barData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                    <XAxis dataKey="name" tick={{ fill: '#64748b', fontSize: 11 }} />
                    <YAxis tick={{ fill: '#64748b', fontSize: 11 }} allowDecimals={false} />
                    <Tooltip content={<CustomTooltip />} />
                    <Bar dataKey="total" name="Chamadas" fill="#007aff" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>
        </div>

        {/* ── Últimas Chamadas URA ────────────────────────────────────────── */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              Últimas Chamadas da URA
              {wsStatus === 'live' && (
                <span style={{ fontSize: '0.72rem', color: '#34c759', marginLeft: 10 }}>
                  ● ao vivo
                </span>
              )}
            </span>
          </div>

          <div className="card-body" style={{ padding: '0 20px 20px' }}>
            <div className="recent-results-list">
              {calls.length === 0 ? (
                <p className="text-muted" style={{ textAlign: 'center', padding: 32 }}>
                  Nenhuma chamada URA registrada
                </p>
              ) : calls.slice(0, 20).map(c => (
                <div key={c.id} className="result-item">
                  <div className="result-status-dot"
                    style={{ background: c.jiraIssueKey ? '#34c759' : '#94a3b8' }} />
                  <span className="result-phone" style={{ minWidth: 130 }}>
                    {c.callerNumber}
                  </span>
                  <span style={{ fontSize: '0.76rem', color: 'var(--text-muted)', flex: 1 }}>
                    {c.clientName ?? 'Cliente desconhecido'}
                  </span>
                  {c.jiraIssueKey ? (
                    <span className="badge" style={{
                      background: 'rgba(52,199,89,0.15)', color: '#34c759',
                      border: '1px solid rgba(52,199,89,0.3)',
                    }}>
                      🎫 {c.jiraIssueKey}
                    </span>
                  ) : (
                    <span className="badge" style={{
                      background: 'rgba(148,163,184,0.1)', color: '#94a3b8',
                      border: '1px solid rgba(148,163,184,0.2)',
                    }}>
                      Sem Jira
                    </span>
                  )}
                  <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', minWidth: 50, textAlign: 'right' }}>
                    {c.callDurationSecs ?? 0}s
                  </span>
                  <span className="result-time">{fmt(c.callDate)}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

      </div>
    </>
  );
}

// ─── Sub-components ─────────────────────────────────────────────────────────

function KpiCard({ icon: Icon, value, label, badge, badgeClass }: {
  icon: LucideIcon; value: string | number;
  label: string; badge: string; badgeClass: string;
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

function EmptyChart({ msg }: { msg: string }) {
  return (
    <p className="text-muted" style={{ textAlign: 'center', padding: '40px 0' }}>
      {msg}
    </p>
  );
}
