import { useEffect, useRef, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { CustomerProfileSummaryRow, CustomerProfileDetail, Page } from '../api/types';
import { todayIso, daysAgoIso } from './ReportsQueueTab';

function fmtDateTime(value: string | null) {
  return value ? new Date(value).toLocaleString('pt-BR') : '—';
}

function fmt(value: number | null | undefined) {
  return value == null ? '—' : String(value);
}

/**
 * CustomerProfileReportTab — "Perfil do cliente" (Fase 27): quem mais liga/conversa, top
 * assuntos e histórico, agrupado por ANI normalizado (ver {@code AniNormalizer} no backend) —
 * GAP CONHECIDO: sem identidade de AD (Fase 14, inexistente), voz e chat só se correlacionam
 * quando o telefone informado no chat normaliza para o mesmo dígito da ligação.
 */
export function CustomerProfileReportTab() {
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(todayIso());
  const [rows, setRows] = useState<CustomerProfileSummaryRow[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const searchSeq = useRef(0);

  const [selected, setSelected] = useState<CustomerProfileDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  // Descarta a resposta de um "Ver histórico" antigo que chegue depois de um mais novo (mesmo
  // padrão de searchSeq acima, aplicado aqui porque openDetail é uma segunda chamada de rede
  // independente — clicar em dois clientes em sequência rápida pode fazer a resposta do primeiro
  // chegar depois da do segundo).
  const detailSeq = useRef(0);

  const search = (p = 0) => {
    const seq = ++searchSeq.current;
    setLoading(true);
    setError('');
    api.get<Page<CustomerProfileSummaryRow>>('/callcenter/reports/customer-profile', {
      params: { from, to, page: p, size: 20 },
    })
      .then(({ data }) => {
        if (seq !== searchSeq.current) return;
        setRows(data.content ?? []);
        setTotalPages(data.totalPages);
        setPage(data.number);
      })
      .catch(err => { if (seq === searchSeq.current) setError(getErrorMessage(err, 'Falha ao carregar clientes')); })
      .finally(() => { if (seq === searchSeq.current) setLoading(false); });
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { search(0); }, []);

  const openDetail = (contact: string) => {
    const seq = ++detailSeq.current;
    setDetailLoading(true);
    setDetailError('');
    setSelected(null);
    api.get<CustomerProfileDetail>('/callcenter/reports/customer-profile/detail', {
      params: { contact, from, to },
    })
      .then(({ data }) => { if (seq === detailSeq.current) setSelected(data); })
      .catch(err => { if (seq === detailSeq.current) setDetailError(getErrorMessage(err, 'Falha ao carregar histórico do cliente')); })
      .finally(() => { if (seq === detailSeq.current) setDetailLoading(false); });
  };

  return (
    <>
      {error && <p style={{ color: 'var(--danger, #c0392b)' }}>{error}</p>}

      <section style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <label>De <input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>Até <input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
        <button type="button" onClick={() => search(0)} disabled={loading}>{loading ? 'Buscando…' : 'Buscar'}</button>
      </section>

      <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 12 }}>
        <thead>
          <tr>
            <th align="left">Contato</th>
            <th align="right">Chamadas</th>
            <th align="right">Chats</th>
            <th align="left">1º contato</th>
            <th align="left">Último contato</th>
            <th align="right">NPS médio</th>
            <th align="left">Assunto mais comum</th>
            <th align="left">Ações</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.normalizedId}>
              <td>{r.displayContact ?? r.normalizedId}</td>
              <td align="right">{r.totalChamadas}</td>
              <td align="right">{r.totalChats}</td>
              <td>{fmtDateTime(r.primeiroContato)}</td>
              <td>{fmtDateTime(r.ultimoContato)}</td>
              <td align="right">{fmt(r.npsMedio)}</td>
              <td>{r.topAssunto ?? '—'}</td>
              <td><button type="button" onClick={() => openDetail(r.normalizedId)}>Ver histórico</button></td>
            </tr>
          ))}
          {rows.length === 0 && !loading && (
            <tr><td colSpan={8} style={{ textAlign: 'center', padding: 12 }}>Sem clientes no período.</td></tr>
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

      {detailLoading && <p>Carregando histórico…</p>}
      {detailError && <p style={{ color: 'var(--danger, #c0392b)' }}>{detailError}</p>}

      {selected && (
        <section style={{ marginTop: 24, borderTop: '1px solid #ddd', paddingTop: 16 }}>
          <h3>Histórico — {selected.displayContact ?? selected.normalizedId}</h3>
          <p>
            {selected.totalChamadas} chamada(s) · {selected.totalChats} chat(s) · NPS médio: {fmt(selected.npsMedio)}
          </p>

          {selected.topAssuntos.length > 0 && (
            <>
              <h4>Top assuntos</h4>
              <ul>
                {selected.topAssuntos.map(a => <li key={a.assunto}>{a.assunto} ({a.total})</li>)}
              </ul>
            </>
          )}

          {selected.chamadas.length > 0 && (
            <>
              <h4>Chamadas</h4>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th align="left">Início</th>
                    <th align="left">Fila</th>
                    <th align="left">Agente</th>
                    <th align="right">NPS</th>
                    <th align="left">Tabulação</th>
                  </tr>
                </thead>
                <tbody>
                  {selected.chamadas.map(c => (
                    <tr key={c.interactionId}>
                      <td>{fmtDateTime(c.queuedAt)}</td>
                      <td>{c.queueName ?? '—'}</td>
                      <td>{c.agentName ?? '—'}</td>
                      <td align="right">{fmt(c.npsScore)}</td>
                      <td>{c.dispositionLabel ?? c.categoriaAssunto ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}

          {selected.chats.length > 0 && (
            <>
              <h4>Chats</h4>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th align="left">Início</th>
                    <th align="left">Fila</th>
                    <th align="left">Agente</th>
                    <th align="left">Tabulação</th>
                  </tr>
                </thead>
                <tbody>
                  {selected.chats.map(c => (
                    <tr key={c.sessionId}>
                      <td>{fmtDateTime(c.startedAt)}</td>
                      <td>{c.queueName ?? '—'}</td>
                      <td>{c.agentName ?? '—'}</td>
                      <td>{c.dispositionLabel ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </section>
      )}
    </>
  );
}
