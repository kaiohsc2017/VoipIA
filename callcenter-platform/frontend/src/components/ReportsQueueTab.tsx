import { useEffect, useState } from 'react';
import { TrendingUp, RefreshCw } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type { CcQueue, QueuePeriodMetrics, QueuePeriodComparison, ReportGranularity } from '../api/types';

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgoIso(days: number) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

function fmt(value: number | null | undefined, suffix = '') {
  return value == null ? '—' : `${value}${suffix}`;
}

interface ReportsQueueTabProps {
  isAdmin: boolean;
}

/**
 * ReportsQueueTab — sub-fase 9a da Fase 9 (Relatórios analíticos): só o agregado de fila de
 * voz nesta fatia (agente/fluxo/chat, timeline omnicanal, exportação e agendamento ficam para
 * fatias futuras). Sem gráfico — este app não tem `recharts` nas dependências (mesma decisão já
 * registrada em InsightsDashboardTab.tsx); tabela cobre a necessidade desta primeira entrega.
 */
export function ReportsQueueTab({ isAdmin }: ReportsQueueTabProps) {
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [selectedQueueId, setSelectedQueueId] = useState<number | ''>('');
  const [granularity, setGranularity] = useState<ReportGranularity>('day');
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(todayIso());
  const [rows, setRows] = useState<QueuePeriodMetrics[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const [compareAFrom, setCompareAFrom] = useState(daysAgoIso(60));
  const [compareATo, setCompareATo] = useState(daysAgoIso(31));
  const [compareBFrom, setCompareBFrom] = useState(daysAgoIso(30));
  const [compareBTo, setCompareBTo] = useState(todayIso());
  const [comparison, setComparison] = useState<QueuePeriodComparison | null>(null);
  const [comparing, setComparing] = useState(false);

  const [reprocessFrom, setReprocessFrom] = useState(daysAgoIso(7));
  const [reprocessTo, setReprocessTo] = useState(todayIso());
  const [reprocessing, setReprocessing] = useState(false);

  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas')
      .then(({ data }) => setQueues(data))
      .catch(() => setQueues([]));
  }, []);

  const load = () => {
    if (!selectedQueueId) {
      setRows([]);
      return;
    }
    setLoading(true);
    setError('');
    api.get<QueuePeriodMetrics[]>('/callcenter/reports/queues', {
      params: { queueId: selectedQueueId, from, to, granularity },
    })
      .then(({ data }) => setRows(data))
      .catch(err => setError(getErrorMessage(err, 'Falha ao carregar relatório')))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, [selectedQueueId, granularity, from, to]);

  const runComparison = () => {
    if (!selectedQueueId) return;
    setComparing(true);
    setError('');
    api.get<QueuePeriodComparison>('/callcenter/reports/queues/compare', {
      params: {
        queueId: selectedQueueId,
        periodAFrom: compareAFrom, periodATo: compareATo,
        periodBFrom: compareBFrom, periodBTo: compareBTo,
      },
    })
      .then(({ data }) => setComparison(data))
      .catch(err => setError(getErrorMessage(err, 'Falha ao comparar períodos')))
      .finally(() => setComparing(false));
  };

  const reprocess = () => {
    setReprocessing(true);
    setError('');
    api.post('/callcenter/reports/reprocess', { from: reprocessFrom, to: reprocessTo })
      .then(() => load())
      .catch(err => setError(getErrorMessage(err, 'Falha ao reprocessar')))
      .finally(() => setReprocessing(false));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <TrendingUp size={20} />
        <h2 style={{ margin: 0 }}>Relatórios — Fila (voz)</h2>
      </div>

      {error && <p style={{ color: 'var(--danger, #c0392b)' }}>{error}</p>}

      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>
          Fila
          <select value={selectedQueueId} onChange={e => setSelectedQueueId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Selecione…</option>
            {queues.map(q => <option key={q.id} value={q.id}>{q.displayName}</option>)}
          </select>
        </label>
        <label>
          Granularidade
          <select value={granularity} onChange={e => setGranularity(e.target.value as ReportGranularity)}>
            <option value="day">Dia</option>
            <option value="week">Semana</option>
            <option value="month">Mês</option>
            <option value="year">Ano</option>
          </select>
        </label>
        <label>De <input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>Até <input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
      </section>

      {loading && <p>Carregando…</p>}

      {!loading && selectedQueueId && (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th align="left">Período</th>
              <th align="right">Recebidas</th>
              <th align="right">Atendidas</th>
              <th align="right">Abandonadas</th>
              <th align="right">Taxa abandono</th>
              <th align="right">ASA (s)</th>
              <th align="right">TMA (s)</th>
              <th align="right">Nível de serviço</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.periodLabel}>
                <td>{r.periodLabel}</td>
                <td align="right">{r.received}</td>
                <td align="right">{r.answered}</td>
                <td align="right">{r.abandoned}</td>
                <td align="right">{fmt(r.abandonRatePct, '%')}</td>
                <td align="right">{fmt(r.avgWaitSeconds)}</td>
                <td align="right">{fmt(r.avgTalkSeconds)}</td>
                <td align="right">{fmt(r.serviceLevelPct, '%')}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={8} style={{ textAlign: 'center', padding: 12 }}>Sem dados no período.</td></tr>
            )}
          </tbody>
        </table>
      )}
      {!selectedQueueId && <p>Selecione uma fila para ver o relatório.</p>}

      <section>
        <h3>Comparar dois períodos</h3>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
          <label>Período A — de <input type="date" value={compareAFrom} onChange={e => setCompareAFrom(e.target.value)} /></label>
          <label>até <input type="date" value={compareATo} onChange={e => setCompareATo(e.target.value)} /></label>
          <label>Período B — de <input type="date" value={compareBFrom} onChange={e => setCompareBFrom(e.target.value)} /></label>
          <label>até <input type="date" value={compareBTo} onChange={e => setCompareBTo(e.target.value)} /></label>
          <button type="button" onClick={runComparison} disabled={!selectedQueueId || comparing}>
            {comparing ? 'Comparando…' : 'Comparar'}
          </button>
        </div>
        {comparison && (
          <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 12 }}>
            <thead>
              <tr><th align="left">Indicador</th><th align="right">Período A</th><th align="right">Período B</th><th align="right">Delta</th></tr>
            </thead>
            <tbody>
              <tr><td>Recebidas</td><td align="right">{comparison.periodA.received}</td><td align="right">{comparison.periodB.received}</td><td align="right">{comparison.receivedDelta}</td></tr>
              <tr><td>Atendidas</td><td align="right">{comparison.periodA.answered}</td><td align="right">{comparison.periodB.answered}</td><td align="right">{comparison.answeredDelta}</td></tr>
              <tr><td>Abandonadas</td><td align="right">{comparison.periodA.abandoned}</td><td align="right">{comparison.periodB.abandoned}</td><td align="right">{comparison.abandonedDelta}</td></tr>
              <tr><td>Taxa de abandono</td><td align="right">{fmt(comparison.periodA.abandonRatePct, '%')}</td><td align="right">{fmt(comparison.periodB.abandonRatePct, '%')}</td><td align="right">{fmt(comparison.abandonRatePctDelta, '%')}</td></tr>
              <tr><td>ASA (s)</td><td align="right">{fmt(comparison.periodA.avgWaitSeconds)}</td><td align="right">{fmt(comparison.periodB.avgWaitSeconds)}</td><td align="right">{fmt(comparison.avgWaitSecondsDelta)}</td></tr>
              <tr><td>TMA (s)</td><td align="right">{fmt(comparison.periodA.avgTalkSeconds)}</td><td align="right">{fmt(comparison.periodB.avgTalkSeconds)}</td><td align="right">{fmt(comparison.avgTalkSecondsDelta)}</td></tr>
              <tr><td>Nível de serviço</td><td align="right">{fmt(comparison.periodA.serviceLevelPct, '%')}</td><td align="right">{fmt(comparison.periodB.serviceLevelPct, '%')}</td><td align="right">{fmt(comparison.serviceLevelPctDelta, '%')}</td></tr>
            </tbody>
          </table>
        )}
      </section>

      {isAdmin && (
        <section style={{ background: '#fff8e1', padding: 12, borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>Reprocessar agregados (admin)</h3>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
            <label>De <input type="date" value={reprocessFrom} onChange={e => setReprocessFrom(e.target.value)} /></label>
            <label>Até <input type="date" value={reprocessTo} onChange={e => setReprocessTo(e.target.value)} /></label>
            <button type="button" onClick={reprocess} disabled={reprocessing}>
              <RefreshCw size={14} /> {reprocessing ? 'Reprocessando…' : 'Reprocessar'}
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
