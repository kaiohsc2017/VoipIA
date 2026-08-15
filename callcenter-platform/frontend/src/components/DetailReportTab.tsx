import { useEffect, useRef, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { CcQueue, CcAgent, CallReportRow, ChatReportRow } from '../api/types';
import { todayIso, daysAgoIso } from './ReportsQueueTab';

interface PageResponse<T> {
  content: T[];
  totalPages: number;
  number: number;
}

function fmtDateTime(value: string | null) {
  return value ? new Date(value).toLocaleString('pt-BR') : '—';
}

/** Baixa um export binário (Excel/PDF, Fase 9c.5) autenticado via `api` (o axios já injeta o
 * Bearer token) — não dá pra usar um <a href> simples porque o endpoint exige JWT. O blob é
 * revogado logo após o clique simulado, sem manter referência viva. */
async function downloadExport(path: string, params: Record<string, unknown>, filename: string) {
  const { data } = await api.get<Blob>(path, { params, responseType: 'blob' });
  const url = window.URL.createObjectURL(data);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

function fmtSeconds(value: number | null) {
  return value == null ? '—' : `${value}s`;
}

/** CallDetailReport — relatório analítico de chamada, linha a linha (Fase 9c). Cada linha aponta
 * pro id de áudio já usado pelo Insights do Call Center (Fase 8) — sem duplicar player/detalhe
 * de transcrição aqui, só o link. */
export function CallDetailReport() {
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [from, setFrom] = useState(daysAgoIso(7));
  const [to, setTo] = useState(todayIso());
  const [queueId, setQueueId] = useState<number | ''>('');
  const [agentId, setAgentId] = useState<number | ''>('');
  const [npsMin, setNpsMin] = useState('');
  const [npsMax, setNpsMax] = useState('');
  const [waitMinSeconds, setWaitMinSeconds] = useState('');
  const [waitMaxSeconds, setWaitMaxSeconds] = useState('');
  const [chosenOptionDigit, setChosenOptionDigit] = useState('');
  const [transcriptText, setTranscriptText] = useState('');
  const [rows, setRows] = useState<CallReportRow[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  // Descarta a resposta de uma busca antiga que chegue depois de uma mais nova (filtro trocado
  // e "Buscar" clicado de novo antes da primeira request voltar) — sem isso, a tabela podia
  // mostrar o resultado de um filtro que o usuário já não tem mais selecionado.
  const searchSeq = useRef(0);

  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => setQueues([]));
    api.get<CcAgent[]>('/callcenter/agentes').then(({ data }) => setAgents(data)).catch(() => setAgents([]));
  }, []);

  const callExportParams = () => ({
    from, to,
    queueId: queueId || undefined,
    agentId: agentId || undefined,
    npsMin: npsMin || undefined,
    npsMax: npsMax || undefined,
    waitMinSeconds: waitMinSeconds || undefined,
    waitMaxSeconds: waitMaxSeconds || undefined,
    chosenOptionDigit: chosenOptionDigit || undefined,
    transcriptText: transcriptText || undefined,
  });

  const search = (p = 0) => {
    const seq = ++searchSeq.current;
    setLoading(true);
    setError('');
    api.get<PageResponse<CallReportRow>>('/callcenter/reports/calls', {
      params: { ...callExportParams(), page: p, size: 20 },
    })
      .then(({ data }) => {
        if (seq !== searchSeq.current) return;
        setRows(data.content ?? []); setTotalPages(data.totalPages); setPage(data.number);
      })
      .catch(err => { if (seq === searchSeq.current) setError(getErrorMessage(err, 'Falha ao carregar relatório')); })
      .finally(() => { if (seq === searchSeq.current) setLoading(false); });
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    search(0);
  }, []);

  return (
    <>
      {error && <p style={{ color: 'var(--danger, #c0392b)' }}>{error}</p>}

      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>De <input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>Até <input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
        <label>
          Fila
          <select value={queueId} onChange={e => setQueueId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Todas</option>
            {queues.map(q => <option key={q.id} value={q.id}>{q.displayName}</option>)}
          </select>
        </label>
        <label>
          Agente
          <select value={agentId} onChange={e => setAgentId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Todos</option>
            {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </label>
        <label>NPS mín. <input type="number" min={0} max={10} value={npsMin} onChange={e => setNpsMin(e.target.value)} style={{ width: 60 }} /></label>
        <label>NPS máx. <input type="number" min={0} max={10} value={npsMax} onChange={e => setNpsMax(e.target.value)} style={{ width: 60 }} /></label>
        <label>Espera mín. (s) <input type="number" min={0} value={waitMinSeconds} onChange={e => setWaitMinSeconds(e.target.value)} style={{ width: 80 }} /></label>
        <label>Espera máx. (s) <input type="number" min={0} value={waitMaxSeconds} onChange={e => setWaitMaxSeconds(e.target.value)} style={{ width: 80 }} /></label>
        <label>Opção escolhida <input type="text" value={chosenOptionDigit} onChange={e => setChosenOptionDigit(e.target.value)} style={{ width: 60 }} /></label>
        <label>Trecho na transcrição <input type="text" value={transcriptText} onChange={e => setTranscriptText(e.target.value)} /></label>
        <button type="button" onClick={() => search(0)} disabled={loading}>{loading ? 'Buscando…' : 'Buscar'}</button>
        <button type="button" onClick={() => downloadExport('/callcenter/reports/calls/export.xlsx', callExportParams(), 'relatorio-chamadas.xlsx')}>
          Exportar Excel
        </button>
        <button type="button" onClick={() => downloadExport('/callcenter/reports/calls/export.pdf', callExportParams(), 'relatorio-chamadas.pdf')}>
          Exportar PDF
        </button>
      </section>

      <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 12 }}>
        <thead>
          <tr>
            <th align="left">Início</th>
            <th align="left">Direção</th>
            <th align="left">Cliente</th>
            <th align="left">Fila</th>
            <th align="left">Agente</th>
            <th align="right">Espera</th>
            <th align="right">NPS</th>
            <th align="left">Fluxo</th>
            <th align="left">Opção</th>
            <th align="left">Categoria/Sentimento</th>
            <th align="left">Áudio</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.interactionId}>
              <td>{fmtDateTime(r.queuedAt)}</td>
              <td>{r.direction === 'OUTBOUND' ? 'Saída' : 'Entrada'}</td>
              <td>{r.ani ?? '—'}</td>
              <td>{r.queueName ?? '—'}</td>
              <td>{r.agentName ?? '—'}</td>
              <td align="right">{fmtSeconds(r.waitSeconds)}</td>
              <td align="right">{r.npsScore ?? '—'}</td>
              <td>{r.flowName ?? '—'}</td>
              <td>{r.chosenOptionLabel ?? r.chosenOptionDigit ?? '—'}</td>
              <td>{r.categoriaAssunto ?? '—'}{r.sentimentoGeral ? ` / ${r.sentimentoGeral}` : ''}</td>
              {/* Sem deep-link pra aba Insights ainda (ela não lê query param de id) — só
                  sinaliza que existe áudio/transcrição; abrir direto fica pra uma fatia futura. */}
              <td>{r.audioFileId ? 'Sim (ver em Insights)' : '—'}</td>
            </tr>
          ))}
          {rows.length === 0 && !loading && (
            <tr><td colSpan={11} style={{ textAlign: 'center', padding: 12 }}>Sem chamadas no período/filtros.</td></tr>
          )}
        </tbody>
      </table>

      {totalPages > 1 && (
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button type="button" onClick={() => search(page - 1)} disabled={page === 0}>Anterior</button>
          <span>Página {page + 1} de {totalPages}</span>
          <button type="button" onClick={() => search(page + 1)} disabled={page + 1 >= totalPages}>Próxima</button>
        </div>
      )}
    </>
  );
}

/** ChatDetailReport — relatório analítico de chat, linha a linha (Fase 9c). Sem NPS (pesquisa de
 * satisfação não liga a chat_session hoje) nem busca de transcrição (chat não tem índice
 * full-text, só o arquivo já exportado ao encerrar a sessão). */
export function ChatDetailReport() {
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [from, setFrom] = useState(daysAgoIso(7));
  const [to, setTo] = useState(todayIso());
  const [queueId, setQueueId] = useState<number | ''>('');
  const [agentId, setAgentId] = useState<number | ''>('');
  const [rows, setRows] = useState<ChatReportRow[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const searchSeq = useRef(0);

  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => setQueues([]));
    api.get<CcAgent[]>('/callcenter/agentes').then(({ data }) => setAgents(data)).catch(() => setAgents([]));
  }, []);

  const chatExportParams = () => ({ from, to, queueId: queueId || undefined, agentId: agentId || undefined });

  const search = (p = 0) => {
    const seq = ++searchSeq.current;
    setLoading(true);
    setError('');
    api.get<PageResponse<ChatReportRow>>('/callcenter/reports/chats', {
      params: { ...chatExportParams(), page: p, size: 20 },
    })
      .then(({ data }) => {
        if (seq !== searchSeq.current) return;
        setRows(data.content ?? []); setTotalPages(data.totalPages); setPage(data.number);
      })
      .catch(err => { if (seq === searchSeq.current) setError(getErrorMessage(err, 'Falha ao carregar relatório')); })
      .finally(() => { if (seq === searchSeq.current) setLoading(false); });
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    search(0);
  }, []);

  return (
    <>
      {error && <p style={{ color: 'var(--danger, #c0392b)' }}>{error}</p>}

      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>De <input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>Até <input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
        <label>
          Fila
          <select value={queueId} onChange={e => setQueueId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Todas</option>
            {queues.map(q => <option key={q.id} value={q.id}>{q.displayName}</option>)}
          </select>
        </label>
        <label>
          Agente
          <select value={agentId} onChange={e => setAgentId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Todos</option>
            {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </label>
        <button type="button" onClick={() => search(0)} disabled={loading}>{loading ? 'Buscando…' : 'Buscar'}</button>
        <button type="button" onClick={() => downloadExport('/callcenter/reports/chats/export.xlsx', chatExportParams(), 'relatorio-chats.xlsx')}>
          Exportar Excel
        </button>
        <button type="button" onClick={() => downloadExport('/callcenter/reports/chats/export.pdf', chatExportParams(), 'relatorio-chats.pdf')}>
          Exportar PDF
        </button>
      </section>

      <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 12 }}>
        <thead>
          <tr>
            <th align="left">Início</th>
            <th align="left">Cliente</th>
            <th align="left">Fila</th>
            <th align="left">Agente</th>
            <th align="left">Tabulação</th>
            <th align="left">Transcrição</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.sessionId}>
              <td>{fmtDateTime(r.startedAt)}</td>
              <td>{r.customerName ?? r.customerRef}</td>
              <td>{r.queueName ?? '—'}</td>
              <td>{r.agentName ?? '—'}</td>
              <td>{r.dispositionName ?? '—'}</td>
              <td>{r.transcriptPath ? 'Exportada' : '—'}</td>
            </tr>
          ))}
          {rows.length === 0 && !loading && (
            <tr><td colSpan={6} style={{ textAlign: 'center', padding: 12 }}>Sem conversas no período/filtros.</td></tr>
          )}
        </tbody>
      </table>

      {totalPages > 1 && (
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button type="button" onClick={() => search(page - 1)} disabled={page === 0}>Anterior</button>
          <span>Página {page + 1} de {totalPages}</span>
          <button type="button" onClick={() => search(page + 1)} disabled={page + 1 >= totalPages}>Próxima</button>
        </div>
      )}
    </>
  );
}
