import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { GamificationReport } from '../api/types';
import { todayIso, daysAgoIso } from './ReportsQueueTab';

function fmt(value: number | null | undefined, suffix = '') {
  return value == null ? '—' : `${value}${suffix}`;
}

/**
 * GamificationReportTab — ranking de agentes por NPS médio (Fase 27, "Gamificação"). Agentes
 * abaixo do volume mínimo aparecem à parte, sem posição — um agente com poucas chamadas e NPS
 * alto não deve ranquear como o melhor da operação (regra explícita do plano).
 */
export function GamificationReportTab() {
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(todayIso());
  const [minCalls, setMinCalls] = useState(5);
  const [report, setReport] = useState<GamificationReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    setError('');
    api.get<GamificationReport>('/callcenter/reports/gamification', { params: { from, to, minCalls } })
      .then(({ data }) => setReport(data))
      .catch(err => setError(getErrorMessage(err, 'Falha ao carregar ranking')))
      .finally(() => setLoading(false));
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, []);

  return (
    <>
      {error && <p style={{ color: 'var(--danger, #c0392b)' }}>{error}</p>}

      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>De <input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>Até <input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
        <label>
          Volume mínimo (atendidas)
          <input type="number" min={0} value={minCalls} onChange={e => setMinCalls(Number(e.target.value) || 0)} style={{ width: 70 }} />
        </label>
        <button type="button" onClick={load} disabled={loading}>{loading ? 'Carregando…' : 'Atualizar'}</button>
      </section>

      {report && (
        <>
          <h3 style={{ marginTop: 16 }}>Ranking (mínimo de {report.minCalls} atendidas no período)</h3>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th align="left">Posição</th>
                <th align="left">Agente</th>
                <th align="right">Atendidas</th>
                <th align="right">Realizadas (saída)</th>
                <th align="right">NPS médio</th>
              </tr>
            </thead>
            <tbody>
              {report.ranking.map(r => (
                <tr key={r.agentId}>
                  <td>{r.position}º</td>
                  <td>{r.agentName}</td>
                  <td align="right">{r.totalAtendidas}</td>
                  <td align="right">{r.totalRealizadas}</td>
                  <td align="right">{fmt(r.npsMedio)}</td>
                </tr>
              ))}
              {report.ranking.length === 0 && (
                <tr><td colSpan={5} style={{ textAlign: 'center', padding: 12 }}>Nenhum agente atingiu o volume mínimo no período.</td></tr>
              )}
            </tbody>
          </table>

          {report.belowMinimum.length > 0 && (
            <>
              <h3 style={{ marginTop: 16 }}>Abaixo do volume mínimo (fora do ranking)</h3>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th align="left">Agente</th>
                    <th align="right">Atendidas</th>
                    <th align="right">Realizadas (saída)</th>
                    <th align="right">NPS médio</th>
                  </tr>
                </thead>
                <tbody>
                  {report.belowMinimum.map(r => (
                    <tr key={r.agentId}>
                      <td>{r.agentName}</td>
                      <td align="right">{r.totalAtendidas}</td>
                      <td align="right">{r.totalRealizadas}</td>
                      <td align="right">{fmt(r.npsMedio)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </>
      )}
    </>
  );
}
