import { useEffect, useState } from 'react';
import { TrendingUp, RefreshCw } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type {
  CcQueue, CcAgent, QueuePeriodMetrics, QueuePeriodComparison,
  AgentPeriodMetrics, AgentPeriodComparison, ReportGranularity,
} from '../api/types';
import { CallDetailReport, ChatDetailReport } from './DetailReportTab';
import { QualityReportTab } from './QualityReportTab';
import { GamificationReportTab } from './GamificationReportTab';
import { CustomerProfileReportTab } from './CustomerProfileReportTab';
import { AgentProductivityReportTab } from './AgentProductivityReportTab';

export function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

export function daysAgoIso(days: number) {
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
 * ReportsQueueTab — aba "Relatórios" da Fase 9 (Relatórios analíticos): sub-fase 9a (fila de
 * voz), 9b (agente de voz), 9c (relatório analítico de chamada/chat, DetailReportTab.tsx) e
 * Fase 26 (relatório de qualidade, QualityReportTab.tsx) num único seletor interno, sem entrada
 * de Sidebar própria por sub-relatório — timeline omnicanal, exportação e agendamento ficam para
 * fatias futuras. Sem gráfico — este app não tem `recharts` nas dependências (mesma decisão já
 * registrada em InsightsDashboardTab.tsx); tabela cobre a necessidade desta entrega.
 *
 * Fase 27 acrescenta 3 sub-relatórios (gamificação, perfil do cliente, produtividade do agente),
 * mesmo padrão de seletor único sem entrada de Sidebar própria.
 */
export function ReportsQueueTab({ isAdmin }: ReportsQueueTabProps) {
  const [view, setView] = useState<
    'queue' | 'agent' | 'call-detail' | 'chat-detail' | 'quality' | 'gamification' | 'customer-profile' | 'productivity'
  >('queue');

  const [reprocessFrom, setReprocessFrom] = useState(daysAgoIso(7));
  const [reprocessTo, setReprocessTo] = useState(todayIso());
  const [reprocessing, setReprocessing] = useState(false);
  const [reprocessError, setReprocessError] = useState('');
  const [reprocessTick, setReprocessTick] = useState(0);

  const reprocess = () => {
    setReprocessing(true);
    setReprocessError('');
    api.post('/callcenter/reports/reprocess', { from: reprocessFrom, to: reprocessTo })
      .then(() => setReprocessTick(t => t + 1))
      .catch(err => setReprocessError(getErrorMessage(err, 'Falha ao reprocessar')))
      .finally(() => setReprocessing(false));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <TrendingUp size={20} />
        <h2 style={{ margin: 0 }}>Relatórios</h2>
      </div>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button type="button" onClick={() => setView('queue')} disabled={view === 'queue'}>Fila (voz)</button>
        <button type="button" onClick={() => setView('agent')} disabled={view === 'agent'}>Agente (voz)</button>
        <button type="button" onClick={() => setView('call-detail')} disabled={view === 'call-detail'}>Chamada (detalhe)</button>
        <button type="button" onClick={() => setView('chat-detail')} disabled={view === 'chat-detail'}>Chat (detalhe)</button>
        <button type="button" onClick={() => setView('quality')} disabled={view === 'quality'}>Qualidade</button>
        <button type="button" onClick={() => setView('gamification')} disabled={view === 'gamification'}>Gamificação</button>
        <button type="button" onClick={() => setView('customer-profile')} disabled={view === 'customer-profile'}>Perfil do cliente</button>
        <button type="button" onClick={() => setView('productivity')} disabled={view === 'productivity'}>Produtividade</button>
      </div>

      {view === 'queue' && <QueueReport reprocessTick={reprocessTick} />}
      {view === 'agent' && <AgentReport reprocessTick={reprocessTick} />}
      {view === 'call-detail' && <CallDetailReport />}
      {view === 'chat-detail' && <ChatDetailReport />}
      {view === 'quality' && <QualityReportTab isAdmin={isAdmin} />}
      {view === 'gamification' && <GamificationReportTab />}
      {view === 'customer-profile' && <CustomerProfileReportTab />}
      {view === 'productivity' && <AgentProductivityReportTab />}

      {isAdmin && (
        <section style={{ background: '#fff8e1', padding: 12, borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>Reprocessar agregados (admin)</h3>
          <p style={{ marginTop: 0, fontSize: 13, color: 'var(--text-muted, #666)' }}>
            Reprocessa fila e agente juntos para o intervalo informado.
          </p>
          {reprocessError && <p style={{ color: 'var(--danger, #c0392b)' }}>{reprocessError}</p>}
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

/** Sub-fase 9a — relatório de fila de voz. */
function QueueReport({ reprocessTick }: { reprocessTick: number }) {
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

  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas')
      .then(({ data }) => setQueues(data))
      .catch(() => setQueues([]));
  }, []);

  useEffect(() => {
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
  }, [selectedQueueId, granularity, from, to, reprocessTick]);

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

  return (
    <>
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
    </>
  );
}

/** Sub-fase 9b — relatório de agente de voz (ocupação/disponibilidade + volume/TMA). */
function AgentReport({ reprocessTick }: { reprocessTick: number }) {
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<number | ''>('');
  const [granularity, setGranularity] = useState<ReportGranularity>('day');
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(todayIso());
  const [rows, setRows] = useState<AgentPeriodMetrics[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const [compareAFrom, setCompareAFrom] = useState(daysAgoIso(60));
  const [compareATo, setCompareATo] = useState(daysAgoIso(31));
  const [compareBFrom, setCompareBFrom] = useState(daysAgoIso(30));
  const [compareBTo, setCompareBTo] = useState(todayIso());
  const [comparison, setComparison] = useState<AgentPeriodComparison | null>(null);
  const [comparing, setComparing] = useState(false);

  useEffect(() => {
    api.get<CcAgent[]>('/callcenter/agentes')
      .then(({ data }) => setAgents(data))
      .catch(() => setAgents([]));
  }, []);

  useEffect(() => {
    if (!selectedAgentId) {
      setRows([]);
      return;
    }
    setLoading(true);
    setError('');
    api.get<AgentPeriodMetrics[]>('/callcenter/reports/agents', {
      params: { agentId: selectedAgentId, from, to, granularity },
    })
      .then(({ data }) => setRows(data))
      .catch(err => setError(getErrorMessage(err, 'Falha ao carregar relatório')))
      .finally(() => setLoading(false));
  }, [selectedAgentId, granularity, from, to, reprocessTick]);

  const runComparison = () => {
    if (!selectedAgentId) return;
    setComparing(true);
    setError('');
    api.get<AgentPeriodComparison>('/callcenter/reports/agents/compare', {
      params: {
        agentId: selectedAgentId,
        periodAFrom: compareAFrom, periodATo: compareATo,
        periodBFrom: compareBFrom, periodBTo: compareBTo,
      },
    })
      .then(({ data }) => setComparison(data))
      .catch(err => setError(getErrorMessage(err, 'Falha ao comparar períodos')))
      .finally(() => setComparing(false));
  };

  return (
    <>
      {error && <p style={{ color: 'var(--danger, #c0392b)' }}>{error}</p>}

      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>
          Agente
          <select value={selectedAgentId} onChange={e => setSelectedAgentId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Selecione…</option>
            {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
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

      {!loading && selectedAgentId && (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th align="left">Período</th>
              <th align="right">Atendidas</th>
              <th align="right">TMA (s)</th>
              <th align="right">Ocupado (s)</th>
              <th align="right">Disponível (s)</th>
              <th align="right">Pausa (s)</th>
              <th align="right">Ocupação</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.periodLabel}>
                <td>{r.periodLabel}</td>
                <td align="right">{r.answered}</td>
                <td align="right">{fmt(r.avgTalkSeconds)}</td>
                <td align="right">{r.occupiedSeconds}</td>
                <td align="right">{r.availableSeconds}</td>
                <td align="right">{r.pausedSeconds}</td>
                <td align="right">{fmt(r.occupancyPct, '%')}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={7} style={{ textAlign: 'center', padding: 12 }}>Sem dados no período.</td></tr>
            )}
          </tbody>
        </table>
      )}
      {!selectedAgentId && <p>Selecione um agente para ver o relatório.</p>}

      <section>
        <h3>Comparar dois períodos</h3>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
          <label>Período A — de <input type="date" value={compareAFrom} onChange={e => setCompareAFrom(e.target.value)} /></label>
          <label>até <input type="date" value={compareATo} onChange={e => setCompareATo(e.target.value)} /></label>
          <label>Período B — de <input type="date" value={compareBFrom} onChange={e => setCompareBFrom(e.target.value)} /></label>
          <label>até <input type="date" value={compareBTo} onChange={e => setCompareBTo(e.target.value)} /></label>
          <button type="button" onClick={runComparison} disabled={!selectedAgentId || comparing}>
            {comparing ? 'Comparando…' : 'Comparar'}
          </button>
        </div>
        {comparison && (
          <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 12 }}>
            <thead>
              <tr><th align="left">Indicador</th><th align="right">Período A</th><th align="right">Período B</th><th align="right">Delta</th></tr>
            </thead>
            <tbody>
              <tr><td>Atendidas</td><td align="right">{comparison.periodA.answered}</td><td align="right">{comparison.periodB.answered}</td><td align="right">{comparison.answeredDelta}</td></tr>
              <tr><td>TMA (s)</td><td align="right">{fmt(comparison.periodA.avgTalkSeconds)}</td><td align="right">{fmt(comparison.periodB.avgTalkSeconds)}</td><td align="right">{fmt(comparison.avgTalkSecondsDelta)}</td></tr>
              <tr><td>Ocupado (s)</td><td align="right">{comparison.periodA.occupiedSeconds}</td><td align="right">{comparison.periodB.occupiedSeconds}</td><td align="right">{comparison.occupiedSecondsDelta}</td></tr>
              <tr><td>Disponível (s)</td><td align="right">{comparison.periodA.availableSeconds}</td><td align="right">{comparison.periodB.availableSeconds}</td><td align="right">{comparison.availableSecondsDelta}</td></tr>
              <tr><td>Ocupação</td><td align="right">{fmt(comparison.periodA.occupancyPct, '%')}</td><td align="right">{fmt(comparison.periodB.occupancyPct, '%')}</td><td align="right">{fmt(comparison.occupancyPctDelta, '%')}</td></tr>
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
