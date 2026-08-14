import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { CcAgent, AgentProductivityReport } from '../api/types';
import { todayIso, daysAgoIso } from './ReportsQueueTab';

function fmtDateTime(value: string | null) {
  return value ? new Date(value).toLocaleString('pt-BR') : '—';
}

function fmt(value: number | null | undefined, suffix = '') {
  return value == null ? '—' : `${value}${suffix}`;
}

const STATE_LABELS: Record<string, string> = {
  DISPONIVEL: 'Disponível',
  EM_ATENDIMENTO: 'Em atendimento',
  ACW: 'Pós-atendimento (ACW)',
  PAUSA: 'Pausa',
  OFFLINE: 'Offline',
};

/**
 * AgentProductivityReportTab — "Produtividade do agente" (Fase 27): login/pausas/logout, volume/
 * TMA/NPS e pontos fortes/de melhoria — estes últimos reusam a análise já existente da Fase 8
 * (Insights do Call Center), nunca geram uma chamada de IA nova nesta tela.
 */
export function AgentProductivityReportTab() {
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [agentId, setAgentId] = useState<number | ''>('');
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(todayIso());
  const [report, setReport] = useState<AgentProductivityReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get<CcAgent[]>('/callcenter/agentes').then(({ data }) => setAgents(data)).catch(() => setAgents([]));
  }, []);

  const load = () => {
    if (!agentId) return;
    setLoading(true);
    setError('');
    api.get<AgentProductivityReport>(`/callcenter/reports/agent-productivity/${agentId}`, { params: { from, to } })
      .then(({ data }) => setReport(data))
      .catch(err => setError(getErrorMessage(err, 'Falha ao carregar produtividade')))
      .finally(() => setLoading(false));
  };

  return (
    <>
      {error && <p style={{ color: 'var(--danger, #c0392b)' }}>{error}</p>}

      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>
          Agente
          <select value={agentId} onChange={e => setAgentId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Selecione…</option>
            {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </label>
        <label>De <input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>Até <input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
        <button type="button" onClick={load} disabled={!agentId || loading}>{loading ? 'Carregando…' : 'Consultar'}</button>
      </section>

      {!agentId && <p>Selecione um agente para ver a produtividade.</p>}

      {report && (
        <>
          <h3 style={{ marginTop: 16 }}>Resumo — {report.agentName}</h3>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <tbody>
              <tr><td>Atendidas</td><td align="right">{report.resumo.totalAtendidas}</td></tr>
              <tr><td>Realizadas (saída)</td><td align="right">{report.resumo.totalRealizadas}</td></tr>
              <tr><td>TMA entrada (s)</td><td align="right">{fmt(report.resumo.avgTalkSeconds)}</td></tr>
              <tr><td>TMA saída (s)</td><td align="right">{fmt(report.resumo.avgOutboundTalkSeconds)}</td></tr>
              <tr><td>NPS médio</td><td align="right">{fmt(report.resumo.npsMedio)}</td></tr>
              <tr><td>Ocupação</td><td align="right">{fmt(report.resumo.occupancyPct, '%')}</td></tr>
              <tr><td>Ocupado (s)</td><td align="right">{report.resumo.occupiedSeconds}</td></tr>
              <tr><td>Disponível (s)</td><td align="right">{report.resumo.availableSeconds}</td></tr>
              <tr><td>Pausa (s)</td><td align="right">{report.resumo.pausedSeconds}</td></tr>
              <tr><td>Offline (s)</td><td align="right">{report.resumo.offlineSeconds}</td></tr>
            </tbody>
          </table>

          <h3 style={{ marginTop: 16 }}>Login / pausas / logout</h3>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr><th align="left">Estado</th><th align="left">Motivo</th><th align="left">Início</th><th align="left">Fim</th></tr>
            </thead>
            <tbody>
              {report.timeline.map((t, idx) => (
                <tr key={idx}>
                  <td>{STATE_LABELS[t.state] ?? t.state}</td>
                  <td>{t.pauseReasonLabel ?? '—'}</td>
                  <td>{fmtDateTime(t.startedAt)}</td>
                  <td>{t.endedAt ? fmtDateTime(t.endedAt) : 'Em andamento'}</td>
                </tr>
              ))}
              {report.timeline.length === 0 && (
                <tr><td colSpan={4} style={{ textAlign: 'center', padding: 12 }}>Sem histórico de estado no período.</td></tr>
              )}
            </tbody>
          </table>

          <h3 style={{ marginTop: 16 }}>Pontos fortes e de melhoria</h3>
          <p style={{ fontSize: 13, color: 'var(--text-muted, #666)' }}>
            Reaproveitado da análise de qualidade já existente (Insights do Call Center) — nenhuma chamada de IA nova nesta tela.
            {report.analise.totalChamadas === 0 && ' Sem chamadas avaliadas no período.'}
          </p>
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
            <div>
              <h4>Pontos fortes</h4>
              <ul>
                {report.pontosFortes.map(p => <li key={p}>{p}</li>)}
                {report.pontosFortes.length === 0 && <li>—</li>}
              </ul>
            </div>
            <div>
              <h4>Pontos de melhoria</h4>
              <ul>
                {report.pontosMelhoria.map(p => <li key={p}>{p}</li>)}
                {report.pontosMelhoria.length === 0 && <li>—</li>}
              </ul>
            </div>
          </div>
        </>
      )}
    </>
  );
}
