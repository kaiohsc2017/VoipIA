import { useEffect, useState } from 'react';
import api from '../api/client';
import type { CallRecord, TestResult, AlertCall, PageResponse } from '../api/types';

// Status color helpers
const statusColor: Record<string, string> = {
  SUCESSO:      '#68d391',
  FALHA:        '#fc8181',
  OCUPADO:      '#f6ad55',
  SEM_RESPOSTA: '#94a3b8',
  TIMEOUT:      '#f6ad55',
  INVALIDO:     '#fc8181',
  INDISPONIVEL: '#fc8181',
  RECUSADO:     '#fc8181',
};

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

export default function Dashboard() {
  const [calls, setCalls] = useState<CallRecord[]>([]);
  const [results, setResults] = useState<TestResult[]>([]);
  const [alerts, setAlerts] = useState<AlertCall[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get<PageResponse<CallRecord>>('/calls?page=0&size=10'),
      api.get<PageResponse<TestResult>>('/test-results?page=0&size=50'),
      api.get<PageResponse<AlertCall>>('/alert-calls?page=0&size=20'),
    ])
      .then(([c, r, a]) => {
        setCalls(c.data.content ?? []);
        setResults(r.data.content ?? []);
        setAlerts(a.data.content ?? []);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  // ---- KPI calculations ----
  const today = new Date().toDateString();
  const callsToday = calls.filter(c => new Date(c.callDate).toDateString() === today).length;
  const resultsToday = results.filter(r => new Date(r.executedAt).toDateString() === today);
  const successToday = resultsToday.filter(r => r.status === 'SUCESSO').length;
  const successRate = resultsToday.length > 0
    ? Math.round((successToday / resultsToday.length) * 100)
    : 0;
  const activeAlerts = alerts.filter(a => a.callStatus === 'PENDENTE').length;

  // ---- Status distribution for chart ----
  const statusCounts: Record<string, number> = {};
  results.forEach(r => {
    statusCounts[r.status] = (statusCounts[r.status] ?? 0) + 1;
  });
  const totalResults = results.length || 1;

  const statusBars = [
    { key: 'SUCESSO',      label: 'Sucesso',     color: '#68d391' },
    { key: 'FALHA',        label: 'Falha',       color: '#fc8181' },
    { key: 'OCUPADO',      label: 'Ocupado',     color: '#f6ad55' },
    { key: 'TIMEOUT',      label: 'Timeout',     color: '#9f7aea' },
    { key: 'SEM_RESPOSTA', label: 'Sem Resp.',   color: '#94a3b8' },
  ];

  if (loading) {
    return (
      <div className="loading-state">
        <div className="spinner" />
        Carregando dashboard…
      </div>
    );
  }

  return (
    <>
      <div className="page-header">
        <h1>📊 Dashboard</h1>
        <p>Visão geral do sistema AsteriskIA em tempo real</p>
      </div>
      <div className="page-body">

        {/* KPI Cards */}
        <div className="kpi-grid">
          <KpiCard
            icon="🎫"
            value={callsToday}
            label="Chamadas Hoje"
            badge={callsToday > 0 ? `+${callsToday} hoje` : 'sem chamadas'}
            badgeClass={callsToday > 0 ? 'info' : 'gray'}
          />
          <KpiCard
            icon="📞"
            value={resultsToday.length}
            label="Testes Hoje"
            badge={`${successToday} sucessos`}
            badgeClass="success"
          />
          <KpiCard
            icon="✅"
            value={`${successRate}%`}
            label="Taxa de Sucesso"
            badge={successRate >= 80 ? '🟢 Saudável' : successRate >= 60 ? '🟡 Atenção' : '🔴 Crítico'}
            badgeClass={successRate >= 80 ? 'success' : successRate >= 60 ? 'warning' : 'danger'}
          />
          <KpiCard
            icon="🚨"
            value={activeAlerts}
            label="Alertas Ativos"
            badge={activeAlerts === 0 ? 'Nenhum' : `${activeAlerts} pendente${activeAlerts > 1 ? 's' : ''}`}
            badgeClass={activeAlerts === 0 ? 'success' : 'danger'}
          />
        </div>

        {/* Charts row */}
        <div className="stats-grid">
          {/* Status distribution */}
          <div className="card">
            <div className="card-header">
              <span className="card-title">📈 Distribuição de Status</span>
              <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                {results.length} resultados totais
              </span>
            </div>
            <div className="card-body">
              {results.length === 0 ? (
                <p className="text-muted" style={{ textAlign: 'center', padding: 32 }}>
                  Nenhum resultado de teste ainda
                </p>
              ) : (
                <div className="status-bar-list">
                  {statusBars.map(sb => {
                    const count = statusCounts[sb.key] ?? 0;
                    const pct = Math.round((count / totalResults) * 100);
                    return (
                      <div key={sb.key} className="status-bar-item">
                        <span className="status-bar-label">{sb.label}</span>
                        <div className="status-bar-track">
                          <div
                            className="status-bar-fill"
                            style={{ width: `${pct}%`, background: sb.color }}
                          />
                        </div>
                        <span className="status-bar-count">{count}</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>

          {/* Recent results */}
          <div className="card">
            <div className="card-header">
              <span className="card-title">🕐 Resultados Recentes</span>
            </div>
            <div className="card-body" style={{ padding: '16px 20px' }}>
              {results.length === 0 ? (
                <p className="text-muted" style={{ textAlign: 'center', padding: 32 }}>
                  Nenhum resultado ainda
                </p>
              ) : (
                <div className="recent-results-list">
                  {results.slice(0, 10).map(r => (
                    <div key={r.id} className="result-item">
                      <div
                        className="result-status-dot"
                        style={{ background: statusColor[r.status] ?? '#94a3b8' }}
                      />
                      <span className="result-phone">
                        #{r.numberTest?.id ?? '?'}
                      </span>
                      <span className="badge" style={{
                        background: `${statusColor[r.status] ?? '#94a3b8'}20`,
                        color: statusColor[r.status] ?? '#94a3b8',
                        border: `1px solid ${statusColor[r.status] ?? '#94a3b8'}40`,
                      }}>
                        {r.status}
                      </span>
                      <span className="result-time">{formatDate(r.executedAt)}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Últimas chamadas */}
        {calls.length > 0 && (
          <div className="card mt-3">
            <div className="card-header">
              <span className="card-title">🎫 Últimas Chamadas URA</span>
            </div>
            <div className="table-wrapper" style={{ border: 'none', borderRadius: 0 }}>
              <table>
                <thead>
                  <tr>
                    <th>Data</th>
                    <th>Número</th>
                    <th>Cliente</th>
                    <th>Chamado Jira</th>
                    <th>Status</th>
                    <th>Duração</th>
                  </tr>
                </thead>
                <tbody>
                  {calls.slice(0, 5).map(c => (
                    <tr key={c.id}>
                      <td className="td-muted">{formatDate(c.callDate)}</td>
                      <td className="mono">{c.callerNumber}</td>
                      <td>{c.clientName || <span className="text-muted">—</span>}</td>
                      <td>
                        {c.jiraIssueKey
                          ? <span className="chip">{c.jiraIssueKey}</span>
                          : <span className="text-muted">—</span>}
                      </td>
                      <td><span className="badge badge-info">{c.jiraIssueStatus || 'Aberto'}</span></td>
                      <td className="td-muted">{c.callDurationSecs}s</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </>
  );
}

// ---- KPI Card sub-component ----
function KpiCard({
  icon, value, label, badge, badgeClass,
}: {
  icon: string;
  value: string | number;
  label: string;
  badge: string;
  badgeClass: string;
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
