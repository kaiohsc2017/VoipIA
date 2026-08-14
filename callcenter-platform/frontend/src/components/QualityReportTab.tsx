import { useEffect, useRef, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { CcQueue, CcAgent, CcQualityReportDto, CcHoliday, QualityReportScopeType } from '../api/types';
import { todayIso, daysAgoIso } from './ReportsQueueTab';

interface PageResponse<T> {
  content: T[];
  totalPages: number;
  number: number;
}

function fmt(value: number | null, suffix = '') {
  return value == null ? '—' : `${value}${suffix}`;
}

function fmtDelta(value: number | null) {
  if (value == null) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value}`;
}

/** QualityReportTab — relatório de qualidade do Call Center (Fase 26): agrega notas já
 * computadas por CallEvaluation/CallEvaluationItem (Fase 8), sem chamada de IA nova. Trava de
 * 5 dias úteis (considerando feriados) por escopo — ADMIN isento. */
export function QualityReportTab({ isAdmin }: { isAdmin: boolean }) {
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [scopeType, setScopeType] = useState<QualityReportScopeType>('GERAL');
  const [scopeValue, setScopeValue] = useState('');
  const [dateFrom, setDateFrom] = useState(daysAgoIso(30));
  const [dateTo, setDateTo] = useState(todayIso());
  const [nextAllowedAt, setNextAllowedAt] = useState<string | null>(null);
  const [requesting, setRequesting] = useState(false);
  const [formError, setFormError] = useState('');

  const [reports, setReports] = useState<CcQualityReportDto[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [selected, setSelected] = useState<CcQualityReportDto | null>(null);
  const listSeq = useRef(0);

  const [holidays, setHolidays] = useState<CcHoliday[]>([]);
  const [newHolidayDate, setNewHolidayDate] = useState(todayIso());
  const [newHolidayDesc, setNewHolidayDesc] = useState('');

  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => setQueues([]));
    api.get<CcAgent[]>('/callcenter/agentes').then(({ data }) => setAgents(data)).catch(() => setAgents([]));
    api.get<CcHoliday[]>('/callcenter/quality-reports/holidays').then(({ data }) => setHolidays(data)).catch(() => setHolidays([]));
  }, []);

  const loadReports = (p = 0) => {
    const seq = ++listSeq.current;
    api.get<PageResponse<CcQualityReportDto>>('/callcenter/quality-reports', { params: { page: p, size: 20 } })
      .then(({ data }) => {
        if (seq !== listSeq.current) return;
        setReports(data.content ?? []); setTotalPages(data.totalPages); setPage(data.number);
      })
      .catch(() => { if (seq === listSeq.current) setReports([]); });
  };

  useEffect(() => { loadReports(0); }, []);

  useEffect(() => {
    if (isAdmin) { setNextAllowedAt(null); return; }
    const timeout = setTimeout(() => {
      api.get<{ nextAllowedAt?: string }>('/callcenter/quality-reports/next-allowed', {
        params: { scopeType, scopeValue: scopeType === 'GERAL' ? undefined : scopeValue || undefined },
      })
        .then(r => setNextAllowedAt(r.data.nextAllowedAt ?? null))
        .catch(() => setNextAllowedAt(null));
    }, 400);
    return () => clearTimeout(timeout);
  }, [scopeType, scopeValue, isAdmin]);

  const cooldownActive = nextAllowedAt != null && new Date(nextAllowedAt) > new Date();

  const requestReport = async () => {
    if (scopeType !== 'GERAL' && !scopeValue) {
      setFormError('Selecione um valor para o escopo escolhido.');
      return;
    }
    setRequesting(true);
    setFormError('');
    try {
      const { data } = await api.post<CcQualityReportDto>('/callcenter/quality-reports', {
        scopeType, scopeValue: scopeType === 'GERAL' ? null : scopeValue, dateFrom, dateTo,
      });
      setSelected(data);
      loadReports(0);
    } catch (err) {
      setFormError(getErrorMessage(err, 'Falha ao gerar relatório'));
    } finally {
      setRequesting(false);
    }
  };

  const openReport = (id: number) => {
    api.get<CcQualityReportDto>(`/callcenter/quality-reports/${id}`).then(({ data }) => setSelected(data)).catch(() => {});
  };

  const addHoliday = () => {
    api.post<CcHoliday>('/callcenter/quality-reports/holidays', { date: newHolidayDate, description: newHolidayDesc || undefined })
      .then(({ data }) => { setHolidays(h => [...h, data].sort((a, b) => a.holidayDate.localeCompare(b.holidayDate))); setNewHolidayDesc(''); })
      .catch(() => {});
  };

  const removeHoliday = (id: number) => {
    api.delete(`/callcenter/quality-reports/holidays/${id}`)
      .then(() => setHolidays(h => h.filter(x => x.id !== id)))
      .catch(() => {});
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>
          Escopo
          <select value={scopeType} onChange={e => { setScopeType(e.target.value as QualityReportScopeType); setScopeValue(''); }}>
            <option value="GERAL">Toda a operação</option>
            <option value="AGENT">Agente</option>
            <option value="QUEUE">Fila</option>
          </select>
        </label>
        {scopeType === 'AGENT' && (
          <label>
            Agente
            <select value={scopeValue} onChange={e => setScopeValue(e.target.value)}>
              <option value="">Selecione…</option>
              {agents.map(a => <option key={a.id} value={a.name}>{a.name}</option>)}
            </select>
          </label>
        )}
        {scopeType === 'QUEUE' && (
          <label>
            Fila
            <select value={scopeValue} onChange={e => setScopeValue(e.target.value)}>
              <option value="">Selecione…</option>
              {queues.map(q => <option key={q.id} value={q.displayName}>{q.displayName}</option>)}
            </select>
          </label>
        )}
        <label>De <input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)} /></label>
        <label>Até <input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)} /></label>
        <button type="button" onClick={requestReport} disabled={requesting || cooldownActive}>
          {requesting ? 'Gerando…' : cooldownActive ? `Aguarde até ${new Date(nextAllowedAt!).toLocaleString('pt-BR')}` : 'Gerar relatório'}
        </button>
      </section>
      {formError && <p style={{ color: 'var(--danger, #c0392b)' }}>{formError}</p>}

      {selected && (
        <section style={{ background: '#f5f5f5', padding: 16, borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>
            {selected.scopeType === 'GERAL' ? 'Toda a operação' : `${selected.scopeType === 'AGENT' ? 'Agente' : 'Fila'}: ${selected.scopeValue}`}
            {' '}({selected.dateFrom} a {selected.dateTo})
          </h3>
          <p>
            Nota média: <strong>{fmt(selected.content.notaMedia)}</strong>
            {selected.evolution && <> ({fmtDelta(selected.evolution.notaMediaDelta)} vs. execução anterior)</>}
            {' — '}{selected.content.totalAvaliacoes} avaliações, {selected.content.totalReprovadas} reprovadas.
          </p>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr><th align="left">Item</th><th align="right">Média</th>{selected.evolution && <th align="right">Delta</th>}</tr>
            </thead>
            <tbody>
              {selected.content.notaPorItem.map(item => {
                const delta = selected.evolution?.itens.find(i => i.itemId === item.itemId);
                return (
                  <tr key={item.itemId}>
                    <td>{item.pergunta ?? `Item #${item.itemId}`}</td>
                    <td align="right">{fmt(item.media)}</td>
                    {selected.evolution && <td align="right">{delta ? fmtDelta(delta.delta) : '—'}</td>}
                  </tr>
                );
              })}
              {selected.content.notaPorItem.length === 0 && (
                <tr><td colSpan={selected.evolution ? 3 : 2} style={{ textAlign: 'center', padding: 8 }}>Sem avaliações no período/escopo.</td></tr>
              )}
            </tbody>
          </table>
        </section>
      )}

      <section>
        <h3>Execuções anteriores</h3>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th align="left">Gerado em</th><th align="left">Escopo</th><th align="left">Período</th>
              <th align="right">Nota média</th><th align="left">Solicitante</th>
            </tr>
          </thead>
          <tbody>
            {reports.map(r => (
              <tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => openReport(r.id)}>
                <td>{new Date(r.requestedAt).toLocaleString('pt-BR')}</td>
                <td>{r.scopeType === 'GERAL' ? 'Toda a operação' : `${r.scopeType === 'AGENT' ? 'Agente' : 'Fila'}: ${r.scopeValue}`}</td>
                <td>{r.dateFrom} a {r.dateTo}</td>
                <td align="right">{fmt(r.content.notaMedia)}</td>
                <td>{r.requestedBy}</td>
              </tr>
            ))}
            {reports.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', padding: 12 }}>Nenhum relatório gerado ainda.</td></tr>}
          </tbody>
        </table>
        {totalPages > 1 && (
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <button type="button" onClick={() => loadReports(page - 1)} disabled={page === 0}>Anterior</button>
            <span>Página {page + 1} de {totalPages}</span>
            <button type="button" onClick={() => loadReports(page + 1)} disabled={page + 1 >= totalPages}>Próxima</button>
          </div>
        )}
      </section>

      <section style={{ background: '#fff8e1', padding: 12, borderRadius: 8 }}>
        <h3 style={{ marginTop: 0 }}>Calendário de feriados</h3>
        <p style={{ marginTop: 0, fontSize: 13, color: 'var(--text-muted, #666)' }}>
          Usado na trava de 5 dias úteis entre execuções do mesmo escopo.
        </p>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
          <label>Data <input type="date" value={newHolidayDate} onChange={e => setNewHolidayDate(e.target.value)} /></label>
          <label>Descrição <input type="text" value={newHolidayDesc} onChange={e => setNewHolidayDesc(e.target.value)} /></label>
          <button type="button" onClick={addHoliday}>Adicionar</button>
        </div>
        <ul>
          {holidays.map(h => (
            <li key={h.id}>
              {h.holidayDate} — {h.description ?? 'sem descrição'}
              {' '}<button type="button" onClick={() => removeHoliday(h.id)}>Remover</button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
