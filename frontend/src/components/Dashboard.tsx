import { useEffect, useState, useCallback } from 'react';
import type { LucideIcon } from 'lucide-react';
import {
  Ticket, PhoneCall, CheckCircle2, AlertTriangle, Timer, Radio, Globe, DollarSign,
} from 'lucide-react';
import {
  AreaChart, Area, BarChart, Bar, LineChart, Line, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import type { TooltipContentProps, PieLabelRenderProps } from 'recharts';
import type { ValueType, NameType } from 'recharts/types/component/DefaultTooltipContent';
import api from '../api/client';
import { connectWebSocket, subscribe } from '../api/websocket';
import { useAuthSession } from '../hooks/useAuthSession';
import type { CallRecord, TestResult, AlertCall, PageResponse, MonthlyCostSummary, InsightMonthlyCostSummary } from '../api/types';

const COST_MONTH_NAMES = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun', 'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez'];

// ─── Constants ──────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  SUCESSO: '#34c759', FALHA: '#ff6b6b', OCUPADO: '#ff9f0a',
  SEM_RESPOSTA: '#94a3b8', TIMEOUT: '#9f7aea', INVALIDO: '#ff6b6b',
  INDISPONIVEL: '#a0aec0', RECUSADO: '#ff6b6b',
};
const PIE_COLORS = ['#34c759', '#ff6b6b', '#ff9f0a', '#9f7aea', '#94a3b8'];
const ALERT_STATUS_COLOR: Record<string, string> = {
  CONCLUIDA: '#34c759', PENDENTE: '#ff9f0a', FALHA: '#ff6b6b', ERRO: '#ff6b6b',
};
const DAYS_PT = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'];

function fmt(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}
function fmtHour(iso: string) {
  return new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
}

// ─── Heatmap (resultados por hora × dia-da-semana) ─────────────────────────

interface HeatCell { count: number; success: number }

function buildHeatmap(results: TestResult[]): HeatCell[][] {
  // [dayOfWeek][hour]
  const grid: HeatCell[][] = Array.from({ length: 7 }, () =>
    Array.from({ length: 24 }, () => ({ count: 0, success: 0 }))
  );
  results.forEach(r => {
    const d = new Date(r.executedAt);
    const day = d.getDay();
    const hour = d.getHours();
    grid[day][hour].count++;
    if (r.status === 'SUCESSO') grid[day][hour].success++;
  });
  return grid;
}

function heatColor(cell: HeatCell): string {
  if (cell.count === 0) return 'rgba(148,163,184,0.06)';
  const rate = cell.count > 0 ? cell.success / cell.count : 0;
  if (rate >= 0.8) return `rgba(52,199,89,${0.15 + rate * 0.55})`;
  if (rate >= 0.5) return `rgba(246,173,85,${0.15 + (1 - rate) * 0.45})`;
  return `rgba(252,129,129,${0.25 + (1 - rate) * 0.5})`;
}

function Heatmap({ results }: { results: TestResult[] }) {
  const grid = buildHeatmap(results);
  const maxCount = Math.max(...grid.flatMap(row => row.map(c => c.count)), 1);

  return (
    <div style={{ overflowX: 'auto' }}>
      <div style={{ display: 'flex', gap: 4, marginBottom: 6, paddingLeft: 36 }}>
        {Array.from({ length: 24 }, (_, h) => (
          <div key={h} style={{
            width: 22, textAlign: 'center', fontSize: '0.62rem',
            color: 'var(--text-muted)', flexShrink: 0,
          }}>
            {h % 3 === 0 ? `${h}h` : ''}
          </div>
        ))}
      </div>
      {grid.map((row, d) => (
        <div key={d} style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4 }}>
          <div style={{
            width: 32, fontSize: '0.68rem', color: 'var(--text-muted)',
            textAlign: 'right', paddingRight: 4, flexShrink: 0,
          }}>
            {DAYS_PT[d]}
          </div>
          {row.map((cell, h) => (
            <div
              key={h}
              title={`${DAYS_PT[d]} ${h}h — ${cell.count} testes, ${cell.success} sucessos`}
              style={{
                width: 22, height: 22, borderRadius: 4, flexShrink: 0,
                background: heatColor(cell),
                border: '1px solid rgba(148,163,184,0.08)',
                opacity: cell.count === 0 ? 0.6 : 0.5 + 0.5 * (cell.count / maxCount),
                cursor: cell.count > 0 ? 'default' : undefined,
                transition: 'transform .15s',
              }}
            />
          ))}
        </div>
      ))}
      {/* Legend */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12, paddingLeft: 36, fontSize: '0.7rem', color: 'var(--text-muted)' }}>
        <span>Menos</span>
        {[0, 0.25, 0.5, 0.75, 1].map(v => (
          <div key={v} style={{
            width: 16, height: 16, borderRadius: 3,
            background: v === 0 ? 'rgba(148,163,184,0.08)' : `rgba(52,199,89,${0.15 + v * 0.55})`,
            border: '1px solid rgba(148,163,184,0.08)',
          }} />
        ))}
        <span>Mais</span>
        <span style={{ marginLeft: 12 }}>🟢 Sucesso</span>
        <span>🟡 Misto</span>
        <span>🔴 Falha</span>
      </div>
    </div>
  );
}

// ─── Dashboard ──────────────────────────────────────────────────────────────

type ActivityTab = 'tests' | 'calls' | 'alerts';

interface TrunkStatus {
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN';
  rttMs: number;
  checkedAt: string;
}

export default function Dashboard() {
  const [calls, setCalls]     = useState<CallRecord[]>([]);
  const [results, setResults] = useState<TestResult[]>([]);
  const [alerts, setAlerts]   = useState<AlertCall[]>([]);
  const [loading, setLoading] = useState(true);
  const [wsStatus, setWsStatus] = useState<'connecting' | 'live' | 'offline'>('connecting');
  const [activityTab, setActivityTab] = useState<ActivityTab>('tests');
  const [trunkStatus, setTrunkStatus] = useState<TrunkStatus | null>(null);
  const [costSummary, setCostSummary] = useState<{
    ura: MonthlyCostSummary[]; insights: InsightMonthlyCostSummary[]; envios: InsightMonthlyCostSummary[];
  }>({ ura: [], insights: [], envios: [] });

  // Booleans primitivos (não a função hasRead em si) nas deps do useCallback abaixo —
  // useAuthSession() cria um objeto/closures novos a cada render; usar a função direto
  // como dep recriaria loadCosts (e o efeito que a chama) infinitamente.
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
      const [c, r, a] = await Promise.all([
        api.get<PageResponse<CallRecord>>('/calls?page=0&size=20'),
        api.get<PageResponse<TestResult>>('/test-results?page=0&size=200'),
        api.get<PageResponse<AlertCall>>('/alert-calls?page=0&size=20'),
      ]);
      setCalls(c.data.content ?? []);
      setResults(r.data.content ?? []);
      setAlerts(a.data.content ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  /** Evolução mensal de custo de IA das 3 frentes do módulo Financeiro, para o gráfico
   * consolidado + card de acumulado do mês — busca só as frentes que o usuário pode ler,
   * e não deixa uma frente sem permissão (403) quebrar as outras. */
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
      setCalls(prev => [newCall, ...prev].slice(0, 20));
    });
    const unsubResults = subscribe<TestResult>('/topic/test-results', (newResult) => {
      setResults(prev => [newResult, ...prev].slice(0, 200));
    });
    const unsubAlerts = subscribe<AlertCall>('/topic/alerts', (newAlert) => {
      setAlerts(prev => [newAlert, ...prev].slice(0, 20));
    });

    return () => { unsubCalls(); unsubResults(); unsubAlerts(); clearInterval(trunkInterval); };
  }, [loadData, fetchTrunkStatus, loadCosts, showCosts]);

  // ─── KPIs ──────────────────────────────────────────────────────────────────
  const today = new Date().toDateString();
  const callsToday   = calls.filter(c => new Date(c.callDate).toDateString() === today).length;
  const resultsToday = results.filter(r => new Date(r.executedAt).toDateString() === today);
  const successToday = resultsToday.filter(r => r.status === 'SUCESSO').length;
  const successRate  = resultsToday.length > 0
    ? Math.round((successToday / resultsToday.length) * 100) : 0;
  const activeAlerts  = alerts.filter(a => a.callStatus === 'PENDENTE').length;
  const avgDuration   = calls.length > 0
    ? Math.round(calls.reduce((s, c) => s + (c.callDurationSecs ?? 0), 0) / calls.length) : 0;
  const alertsToday   = alerts.filter(a => new Date(a.callDate).toDateString() === today).length;

  // ─── Chart data ────────────────────────────────────────────────────────────
  const hourlyMap: Record<string, { hora: string; SUCESSO: number; FALHA: number; OUTROS: number }> = {};
  results.slice(0, 200).forEach(r => {
    const key = fmtHour(r.executedAt);
    if (!hourlyMap[key]) hourlyMap[key] = { hora: key, SUCESSO: 0, FALHA: 0, OUTROS: 0 };
    if (r.status === 'SUCESSO') hourlyMap[key].SUCESSO++;
    else if (r.status === 'FALHA') hourlyMap[key].FALHA++;
    else hourlyMap[key].OUTROS++;
  });
  const areaData = Object.values(hourlyMap).slice(-16);

  const statusCounts: Record<string, number> = {};
  results.forEach(r => { statusCounts[r.status] = (statusCounts[r.status] ?? 0) + 1; });
  const pieData = Object.entries(statusCounts)
    .map(([name, value]) => ({ name, value }))
    .sort((a, b) => b.value - a.value).slice(0, 5);

  const clientMap: Record<string, number> = {};
  calls.forEach(c => {
    const k = c.clientName || 'Desconhecido';
    clientMap[k] = (clientMap[k] ?? 0) + 1;
  });
  const barData = Object.entries(clientMap)
    .map(([name, total]) => ({ name: name.length > 12 ? name.slice(0, 11) + '…' : name, total }))
    .sort((a, b) => b.total - a.total).slice(0, 6);

  // ─── Custo de IA — 3 frentes do módulo Financeiro (URA/Insights/Análise Sob Demanda) ──
  const costYear = new Date().getFullYear();
  const costChartData = Array.from({ length: 12 }, (_, i) => {
    const rawMonth = `${costYear}-${String(i + 1).padStart(2, '0')}`;
    return {
      month: `${COST_MONTH_NAMES[i]}/${String(costYear).slice(2)}`,
      URA: costSummary.ura.find(m => m.month === rawMonth)?.totalCostUsd ?? 0,
      Insights: costSummary.insights.find(m => m.month === rawMonth)?.totalCostUsd ?? 0,
      'Análise Sob Demanda': costSummary.envios.find(m => m.month === rawMonth)?.totalCostUsd ?? 0,
    };
  });
  const currentMonthKey = `${costYear}-${String(new Date().getMonth() + 1).padStart(2, '0')}`;
  const currentMonthCostTotal =
    (costSummary.ura.find(m => m.month === currentMonthKey)?.totalCostUsd ?? 0) +
    (costSummary.insights.find(m => m.month === currentMonthKey)?.totalCostUsd ?? 0) +
    (costSummary.envios.find(m => m.month === currentMonthKey)?.totalCostUsd ?? 0);
  const hasCostData = costSummary.ura.length + costSummary.insights.length + costSummary.envios.length > 0;

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
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <h1>Dashboard</h1>
          <span style={{
            fontSize: '0.72rem', fontWeight: 600, letterSpacing: 1,
            padding: '3px 10px', borderRadius: 20,
            background: wsStatus === 'live' ? 'rgba(52,199,89,0.15)' : 'rgba(255,107,107,0.15)',
            color: wsStatus === 'live' ? '#34c759' : '#ff6b6b',
            border: `1px solid ${wsStatus === 'live' ? '#34c75940' : '#ff6b6b40'}`,
          }}>
            {wsStatus === 'live' ? '⬤ LIVE' : wsStatus === 'connecting' ? '⬤ Conectando…' : '⬤ Offline'}
          </span>
        </div>
        <p>Visão geral do sistema AsteriskIA em tempo real</p>
      </div>

      <div className="page-body">

        {/* ── KPI Cards (6) ──────────────────────────────────────────────── */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
          gap: 24, marginBottom: 24,
        }}>
          <KpiCard icon={Ticket} value={callsToday}      label="Chamadas URA Hoje"
            badge={callsToday > 0 ? `+${callsToday} hoje` : 'nenhuma'}
            badgeClass={callsToday > 0 ? 'info' : 'gray'} />
          <KpiCard icon={PhoneCall} value={resultsToday.length} label="Testes Hoje"
            badge={`${successToday} sucessos`} badgeClass="success" />
          <KpiCard icon={CheckCircle2} value={`${successRate}%`} label="Taxa de Sucesso"
            badge={successRate >= 80 ? 'Saudável' : successRate >= 60 ? 'Atenção' : 'Crítico'}
            badgeClass={successRate >= 80 ? 'success' : successRate >= 60 ? 'warning' : 'danger'} />
          <KpiCard icon={AlertTriangle} value={activeAlerts}     label="Alertas Ativos"
            badge={activeAlerts === 0 ? 'Nenhum' : `${activeAlerts} pendente${activeAlerts > 1 ? 's' : ''}`}
            badgeClass={activeAlerts === 0 ? 'success' : 'danger'} />
          <KpiCard icon={Timer} value={`${avgDuration}s`} label="Duração Média URA"
            badge={calls.length > 0 ? `${calls.length} chamadas` : 'sem dados'}
            badgeClass="info" />
          <KpiCard icon={Radio} value={alertsToday}      label="Alertas Zabbix Hoje"
            badge={alertsToday === 0 ? 'Nenhum' : `${alertsToday} disparo${alertsToday > 1 ? 's' : ''}`}
            badgeClass={alertsToday === 0 ? 'success' : 'warning'} />
          <KpiCard
            icon={Globe}
            value={trunkStatus == null ? '…' : trunkStatus.status === 'ONLINE' ? 'Online' : trunkStatus.status === 'OFFLINE' ? 'Offline' : '—'}
            label="Tronco SIP"
            badge={trunkStatus == null ? 'Verificando…' : trunkStatus.status === 'ONLINE' && trunkStatus.rttMs >= 0 ? `${trunkStatus.rttMs}ms RTT` : trunkStatus.status === 'OFFLINE' ? 'Sem resposta' : 'Indisponível'}
            badgeClass={trunkStatus?.status === 'ONLINE' ? 'success' : trunkStatus?.status === 'OFFLINE' ? 'danger' : 'gray'}
          />
          {showCosts && (
            <KpiCard icon={DollarSign} value={`US$ ${currentMonthCostTotal.toFixed(2)}`}
              label="Custo IA acumulado (mês)" badge="URA + Insights + Envios" badgeClass="info" />
          )}
        </div>

        {/* ── Area Chart ─────────────────────────────────────────────────── */}
        <div className="card" style={{ marginBottom: 20 }}>
          <div className="card-header">
            <span className="card-title">Testes de Conectividade — Linha do Tempo</span>
            <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              Últimos {results.length} resultados
            </span>
          </div>
          <div className="card-body" style={{ padding: '8px 16px 20px' }}>
            {areaData.length === 0 ? <EmptyChart msg="Nenhum dado de teste disponível" /> : (
              <ResponsiveContainer width="100%" height={220}>
                <AreaChart data={areaData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <defs>
                    <linearGradient id="gSuccess" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#34c759" stopOpacity={0.3} />
                      <stop offset="95%" stopColor="#34c759" stopOpacity={0.02} />
                    </linearGradient>
                    <linearGradient id="gFail" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#ff6b6b" stopOpacity={0.3} />
                      <stop offset="95%" stopColor="#ff6b6b" stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.08)" />
                  <XAxis dataKey="hora" tick={{ fill: '#64748b', fontSize: 11 }} />
                  <YAxis tick={{ fill: '#64748b', fontSize: 11 }} allowDecimals={false} />
                  <Tooltip content={<CustomTooltip />} />
                  <Legend wrapperStyle={{ fontSize: '0.82rem', color: '#94a3b8' }} />
                  <Area type="monotone" dataKey="SUCESSO" stroke="#34c759" fill="url(#gSuccess)" strokeWidth={2} />
                  <Area type="monotone" dataKey="FALHA"   stroke="#ff6b6b" fill="url(#gFail)"    strokeWidth={2} />
                  <Area type="monotone" dataKey="OUTROS"  stroke="#9f7aea" fill="none"            strokeWidth={1.5} strokeDasharray="4 2" />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>

        {/* ── Charts Row ─────────────────────────────────────────────────── */}
        <div className="stats-grid" style={{ marginBottom: 20 }}>
          {/* Pie */}
          <div className="card">
            <div className="card-header">
              <span className="card-title">Distribuição de Status</span>
            </div>
            <div className="card-body" style={{ padding: '8px 16px 20px' }}>
              {pieData.length === 0 ? <EmptyChart msg="Sem dados de status" /> : (
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie data={pieData} cx="50%" cy="50%" innerRadius={52} outerRadius={80}
                      dataKey="value" nameKey="name" paddingAngle={3}
                      label={({ name, percent }: PieLabelRenderProps) =>
                        percent != null ? `${name ?? ''} ${(Number(percent) * 100).toFixed(0)}%` : (name ?? '')
                      }
                      labelLine={false}
                    >
                      {pieData.map((entry, i) => (
                        <Cell key={entry.name} fill={STATUS_COLORS[entry.name] ?? PIE_COLORS[i % PIE_COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip formatter={(v: ValueType | undefined, n: NameType | undefined) => [v, n]} />
                  </PieChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>

          {/* Bar */}
          <div className="card">
            <div className="card-header">
              <span className="card-title">Chamadas por Cliente</span>
            </div>
            <div className="card-body" style={{ padding: '8px 16px 20px' }}>
              {barData.length === 0 ? <EmptyChart msg="Nenhuma chamada registrada" /> : (
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={barData} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.08)" />
                    <XAxis dataKey="name" tick={{ fill: '#64748b', fontSize: 11 }} />
                    <YAxis tick={{ fill: '#64748b', fontSize: 11 }} allowDecimals={false} />
                    <Tooltip content={<CustomTooltip />} />
                    <Bar dataKey="total" radius={[4, 4, 0, 0]}>
                      <defs>
                        <linearGradient id="barGrad" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor="#007aff" />
                          <stop offset="100%" stopColor="#4da8ff" />
                        </linearGradient>
                      </defs>
                      {barData.map((entry) => (
                        <Cell key={entry.name} fill="url(#barGrad)" />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>
        </div>

        {/* ── Heatmap ────────────────────────────────────────────────────── */}
        <div className="card" style={{ marginBottom: 20 }}>
          <div className="card-header">
            <span className="card-title">Mapa de Calor — Testes por Hora e Dia</span>
            <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              {results.length} registros · verde = sucesso · vermelho = falha
            </span>
          </div>
          <div className="card-body" style={{ padding: '16px 20px 20px' }}>
            {results.length === 0
              ? <EmptyChart msg="Nenhum dado disponível para o mapa de calor" />
              : <Heatmap results={results} />
            }
          </div>
        </div>

        {/* ── Evolução de Custos de IA — 3 frentes (módulo Financeiro) ─────── */}
        {showCosts && (
          <div className="card" style={{ marginBottom: 20 }}>
            <div className="card-header">
              <span className="card-title">Evolução de Custos de IA — mês a mês ({costYear})</span>
              <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                URA · Insights · Análise Sob Demanda
              </span>
            </div>
            <div className="card-body" style={{ padding: '8px 16px 20px' }}>
              {!hasCostData ? <EmptyChart msg="Sem custo de IA registrado no período" /> : (
                <ResponsiveContainer width="100%" height={240}>
                  <LineChart data={costChartData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.08)" />
                    <XAxis dataKey="month" tick={{ fill: '#64748b', fontSize: 11 }} />
                    <YAxis tick={{ fill: '#64748b', fontSize: 11 }} tickFormatter={v => `US$${v}`} />
                    <Tooltip
                      formatter={(value: ValueType | undefined) =>
                        typeof value === 'number' ? `US$ ${value.toFixed(2)}` : String(value ?? '')}
                    />
                    <Legend wrapperStyle={{ fontSize: '0.78rem', color: '#94a3b8' }} />
                    <Line type="monotone" dataKey="URA" stroke="#007aff" strokeWidth={2} dot={false} />
                    <Line type="monotone" dataKey="Insights" stroke="#ff9f0a" strokeWidth={2} dot={false} />
                    <Line type="monotone" dataKey="Análise Sob Demanda" stroke="#34c759" strokeWidth={2} dot={false} />
                  </LineChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>
        )}

        {/* ── Últimas Atividades (unificado com tabs) ────────────────────── */}
        <div className="card">
          <div className="card-header" style={{ flexWrap: 'wrap', gap: 8 }}>
            <span className="card-title">
              Últimas Atividades
              {wsStatus === 'live' && (
                <span style={{ fontSize: '0.72rem', color: '#34c759', marginLeft: 10 }}>
                  ● ao vivo
                </span>
              )}
            </span>
            {/* Tabs */}
            <div style={{ display: 'flex', gap: 6 }}>
              {([
                { id: 'tests',  label: 'Testes',          count: results.length },
                { id: 'calls',  label: 'Chamadas URA',     count: calls.length },
                { id: 'alerts', label: 'Alertas Zabbix',   count: alerts.length },
              ] as { id: ActivityTab; label: string; count: number }[]).map(tab => (
                <button
                  key={tab.id}
                  id={`dashboard-tab-${tab.id}`}
                  onClick={() => setActivityTab(tab.id)}
                  style={{
                    padding: '4px 12px', borderRadius: 20, border: 'none',
                    cursor: 'pointer', fontSize: '0.78rem', fontWeight: 600,
                    background: activityTab === tab.id
                      ? 'rgba(0,122,255,0.15)' : 'rgba(255,255,255,0.05)',
                    color: activityTab === tab.id ? 'var(--clr-primary)' : 'var(--text-muted)',
                    outline: activityTab === tab.id ? '1px solid rgba(0,122,255,0.35)' : 'none',
                    transition: 'all .15s',
                  }}
                >
                  {tab.label}
                  <span style={{
                    marginLeft: 6, fontSize: '0.68rem', opacity: 0.7,
                    background: 'rgba(255,255,255,0.08)', padding: '1px 6px', borderRadius: 10,
                  }}>
                    {tab.count}
                  </span>
                </button>
              ))}
            </div>
          </div>

          <div className="card-body" style={{ padding: '0 20px 20px' }}>
            {/* Tab: Testes */}
            {activityTab === 'tests' && (
              <div className="recent-results-list">
                {results.length === 0 ? (
                  <p className="text-muted" style={{ textAlign: 'center', padding: 32 }}>
                    Aguardando primeiros resultados…
                  </p>
                ) : results.slice(0, 15).map(r => (
                  <div key={r.id} className="result-item">
                    <div className="result-status-dot"
                      style={{ background: STATUS_COLORS[r.status] ?? '#94a3b8' }} />
                    <span className="result-phone" style={{ minWidth: 120 }}>
                      {r.numberTest?.phoneNumber ?? `Teste #${r.numberTest?.id}`}
                    </span>
                    <span style={{ fontSize: '0.76rem', color: 'var(--text-muted)', flex: 1 }}>
                      {r.numberTest?.client?.name ?? ''}{r.numberTest?.businessUnit?.name ? ` · ${r.numberTest.businessUnit.name}` : ''}
                    </span>
                    <span className="badge" style={{
                      background: `${STATUS_COLORS[r.status] ?? '#94a3b8'}20`,
                      color: STATUS_COLORS[r.status] ?? '#94a3b8',
                      border: `1px solid ${STATUS_COLORS[r.status] ?? '#94a3b8'}40`,
                    }}>
                      {r.status}
                    </span>
                    {r.sipResponseCode && (
                      <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', minWidth: 40, textAlign: 'right' }}>
                        SIP {r.sipResponseCode}
                      </span>
                    )}
                    <span className="result-time">{fmt(r.executedAt)}</span>
                  </div>
                ))}
              </div>
            )}

            {/* Tab: Chamadas URA */}
            {activityTab === 'calls' && (
              <div className="recent-results-list">
                {calls.length === 0 ? (
                  <p className="text-muted" style={{ textAlign: 'center', padding: 32 }}>
                    Nenhuma chamada URA registrada
                  </p>
                ) : calls.slice(0, 15).map(c => (
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
                    <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', minWidth: 40, textAlign: 'right' }}>
                      {c.callDurationSecs ?? 0}s
                    </span>
                    <span className="result-time">{fmt(c.callDate)}</span>
                  </div>
                ))}
              </div>
            )}

            {/* Tab: Alertas */}
            {activityTab === 'alerts' && (
              <div className="recent-results-list">
                {alerts.length === 0 ? (
                  <p className="text-muted" style={{ textAlign: 'center', padding: 32 }}>
                    Nenhum alerta Zabbix registrado
                  </p>
                ) : alerts.slice(0, 15).map(a => (
                  <div key={a.id} className="result-item">
                    <div className="result-status-dot"
                      style={{ background: ALERT_STATUS_COLOR[a.callStatus] ?? '#94a3b8' }} />
                    <span className="result-phone" style={{ minWidth: 130 }}>
                      {a.phoneNumber}
                    </span>
                    <span style={{
                      fontSize: '0.76rem', color: 'var(--text-muted)',
                      flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    }}>
                      {a.zabbixIncidentSummary ?? a.zabbixHost ?? ''}
                    </span>
                    <span className="badge" style={{
                      background: `${ALERT_STATUS_COLOR[a.callStatus] ?? '#94a3b8'}20`,
                      color: ALERT_STATUS_COLOR[a.callStatus] ?? '#94a3b8',
                      border: `1px solid ${ALERT_STATUS_COLOR[a.callStatus] ?? '#94a3b8'}40`,
                    }}>
                      {a.callStatus}
                    </span>
                    {a.telegramSentAt && (
                      <span title="Telegram enviado" style={{ fontSize: '0.85rem' }}>💬</span>
                    )}
                    <span className="result-time">{fmt(a.callDate)}</span>
                  </div>
                ))}
              </div>
            )}
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
