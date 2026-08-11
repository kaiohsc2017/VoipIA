import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { CcAgentReportDto, CcAgentEvolutionSnapshot } from '../api/types';

interface ReportsTabProps {
  canWrite: boolean;
  isAdmin: boolean;
}

interface PageResponse<T> {
  content: T[];
  totalPages: number;
  number: number;
}

function triggerDownload(data: Blob, filename: string) {
  const url = URL.createObjectURL(new Blob([data], { type: 'application/pdf' }));
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', filename);
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function formatNota(value?: number | null) {
  return value != null ? value.toFixed(1) : '—';
}

function formatDelta(value?: number | null) {
  if (value == null) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(1)}`;
}

const STATUS_LABELS: Record<string, string> = {
  pending: 'Na fila', processing: 'Processando', done: 'Concluído', error: 'Erro',
};

/** Aba "Relatórios" — relatórios de performance por atendente do Call Center (Fase 8,
 * espelha /insights/reports com source='callcenter', V55, para nunca misturar agregados
 * com o Insights Verint mesmo que o nome do atendente coincida). Posse: supervisor só vê
 * os relatórios que ele mesmo pediu; ADMIN vê todos com coluna de solicitante. Cooldown de
 * 5 dias úteis por par (supervisor, atendente) — ADMIN isento.
 *
 * Sem recharts (não é dependência desta SPA, ver InsightsDashboardTab.tsx) — o histórico de
 * evolução usa uma tabela simples em vez de um gráfico de linha. */
export function ReportsTab({ canWrite, isAdmin }: ReportsTabProps) {
  const [view, setView] = useState<'list' | 'detail' | 'history'>('list');
  const [reports, setReports] = useState<CcAgentReportDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);

  const [agentName, setAgentName] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [nextAllowedAt, setNextAllowedAt] = useState<string | null>(null);
  const [requesting, setRequesting] = useState(false);
  const [formError, setFormError] = useState('');

  const [selected, setSelected] = useState<CcAgentReportDto | null>(null);
  const [historyAgent, setHistoryAgent] = useState('');
  const [historyData, setHistoryData] = useState<CcAgentEvolutionSnapshot[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');

  const load = (p = 0) => {
    setLoading(true);
    api.get<PageResponse<CcAgentReportDto>>(`/callcenter/insights/reports?page=${p}&size=20`)
      .then(r => { setReports(r.data.content ?? []); setTotalPages(r.data.totalPages); setPage(r.data.number); })
      .catch(err => { console.error('Erro ao carregar relatórios:', err); setReports([]); })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(0); }, []);

  // Consulta o cooldown assim que o supervisor termina de digitar o nome do
  // atendente — evita deixar o botão "Gerar" habilitado pra só falhar com 429.
  useEffect(() => {
    if (isAdmin || !agentName.trim()) { setNextAllowedAt(null); return; }
    const timeout = setTimeout(() => {
      api.get<{ nextAllowedAt?: string }>(`/callcenter/insights/reports/agent/${encodeURIComponent(agentName)}/next-allowed`)
        .then(r => setNextAllowedAt(r.data.nextAllowedAt ?? null))
        .catch(() => setNextAllowedAt(null));
    }, 400);
    return () => clearTimeout(timeout);
  }, [agentName, isAdmin]);

  const cooldownActive = nextAllowedAt != null && new Date(nextAllowedAt) > new Date();

  const requestReport = async () => {
    if (!agentName.trim() || !dateFrom || !dateTo) {
      setFormError('Atendente e período são obrigatórios.');
      return;
    }
    setRequesting(true);
    setFormError('');
    try {
      await api.post('/callcenter/insights/reports', { agentName: agentName.trim(), dateFrom, dateTo });
      setAgentName(''); setDateFrom(''); setDateTo('');
      load(0);
    } catch (err) {
      setFormError(getErrorMessage(err, 'Falha ao solicitar relatório'));
    } finally {
      setRequesting(false);
    }
  };

  const openDetail = (id: number) => {
    api.get<CcAgentReportDto>(`/callcenter/insights/reports/${id}`)
      .then(r => { setSelected(r.data); setView('detail'); })
      .catch(err => { console.error('Erro ao carregar relatório:', err); alert('Erro ao carregar relatório.'); });
  };

  const exportPdf = async (report: CcAgentReportDto) => {
    try {
      const res = await api.get(`/callcenter/insights/reports/${report.id}/pdf`, { responseType: 'blob' });
      triggerDownload(res.data, `relatorio-${report.agentName}-${report.id}.pdf`);
    } catch {
      alert('Erro ao exportar PDF — verifique se o relatório já foi concluído.');
    }
  };

  const openHistory = (name: string) => {
    setHistoryAgent(name);
    setHistoryLoading(true);
    setHistoryError('');
    api.get<CcAgentEvolutionSnapshot[]>(`/callcenter/insights/reports/agent/${encodeURIComponent(name)}/evolution`)
      .then(r => setHistoryData(r.data))
      .catch(err => setHistoryError(getErrorMessage(err, 'Falha ao carregar histórico do agente')))
      .finally(() => setHistoryLoading(false));
    setView('history');
  };

  if (view === 'history') {
    const notaSeries = historyData.filter(s => s.metricKey === 'nota_total')
      .map(s => ({ data: new Date(s.createdAt).toLocaleDateString('pt-BR'), nota: s.valor }));
    return (
      <>
        <div className="toolbar">
          <div className="toolbar-left">
            <h2 style={{ margin: 0 }}>Histórico do agente — {historyAgent}</h2>
          </div>
          <div className="toolbar-right">
            <button className="btn btn-ghost btn-sm" onClick={() => setView('list')}>← Voltar</button>
          </div>
        </div>
        {historyLoading ? (
          <div className="loading-state"><div className="spinner" />Carregando…</div>
        ) : historyError ? (
          <div className="alert alert-error">{historyError}</div>
        ) : notaSeries.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>Sem histórico de nota para este agente ainda</div>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead><tr><th>Data</th><th>Nota total</th></tr></thead>
              <tbody>
                {notaSeries.map((s, i) => (
                  <tr key={i}>
                    <td>{s.data}</td>
                    <td className="td-muted">{formatNota(s.nota)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </>
    );
  }

  if (view === 'detail' && selected) {
    const content = selected.content;
    const evolution = selected.evolution;
    return (
      <>
        <div className="toolbar">
          <div className="toolbar-left">
            <h2 style={{ margin: 0 }}>Relatório — {selected.agentName}</h2>
          </div>
          <div className="toolbar-right">
            <button className="btn btn-ghost btn-sm" onClick={() => openHistory(selected.agentName)}>Histórico do agente</button>
            {selected.status === 'done' && (
              <button className="btn btn-primary btn-sm" onClick={() => exportPdf(selected)}>Exportar PDF</button>
            )}
            <button className="btn btn-ghost btn-sm" onClick={() => setView('list')}>← Voltar</button>
          </div>
        </div>

        <p className="td-muted" style={{ marginBottom: 16 }}>
          Período: {selected.dateFrom} a {selected.dateTo} — status: {STATUS_LABELS[selected.status] ?? selected.status}
          {isAdmin && <> — solicitado por {selected.requestedBy}</>}
        </p>

        {selected.status === 'error' && <div className="alert alert-error" style={{ marginBottom: 16 }}>{selected.errorMsg}</div>}
        {(selected.status === 'pending' || selected.status === 'processing') && (
          <div className="loading-state"><div className="spinner" />Gerando relatório…</div>
        )}

        {content?.aggregate && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 10, marginBottom: 20 }}>
            <div className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Chamadas no período</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700 }}>{content.aggregate.totalChamadas}</div>
            </div>
            <div className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Nota média</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#5856d6' }}>{formatNota(content.aggregate.notaMedia)}</div>
            </div>
            <div className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Auto-fails</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#ff3b30' }}>{content.aggregate.autoFails}</div>
            </div>
          </div>
        )}

        {evolution && (
          <div className="stat-card" style={{ padding: 20, marginBottom: 20 }}>
            <h3 style={{ marginBottom: 12, fontSize: '0.95rem' }}>Evolução desde o último relatório</h3>
            {evolution.partial && (
              <div className="alert alert-warning" style={{ marginBottom: 12 }}>
                Ficha de avaliação alterada entre os períodos — comparação parcial.
              </div>
            )}
            <p style={{ marginBottom: 12 }}>Nota média: {formatDelta(evolution.deltaNotaMedia)}</p>
            {evolution.deltaPorItem.length > 0 && (
              <div className="table-wrapper">
                <table>
                  <thead><tr><th>Item</th><th>Anterior</th><th>Atual</th><th>Delta</th></tr></thead>
                  <tbody>
                    {evolution.deltaPorItem.map(d => (
                      <tr key={d.itemId}>
                        <td>{d.pergunta}</td>
                        <td className="td-muted">{formatNota(d.anterior)}</td>
                        <td className="td-muted">{formatNota(d.atual)}</td>
                        <td>{d.delta != null && d.delta > 0 ? '↑ ' : d.delta != null && d.delta < 0 ? '↓ ' : '= '}{formatDelta(d.delta)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {content?.narrative && (
          <>
            <div className="stat-card" style={{ padding: 20, marginBottom: 16 }}>
              <h3 style={{ marginBottom: 12, fontSize: '0.95rem' }}>Pontos fortes</h3>
              <ul>{content.narrative.pontosFortes.map((p, i) => <li key={i}>{p}</li>)}</ul>
            </div>
            <div className="stat-card" style={{ padding: 20, marginBottom: 16 }}>
              <h3 style={{ marginBottom: 12, fontSize: '0.95rem' }}>Pontos de melhoria</h3>
              <ul>{content.narrative.pontosMelhoria.map((p, i) => <li key={i}>{p}</li>)}</ul>
            </div>
            <div className="stat-card" style={{ padding: 20, marginBottom: 16 }}>
              <h3 style={{ marginBottom: 12, fontSize: '0.95rem' }}>Recomendações</h3>
              <ul>{content.narrative.recomendacoes.map((p, i) => <li key={i}>{p}</li>)}</ul>
            </div>
            {content.narrative.comparacaoTextual && (
              <div className="stat-card" style={{ padding: 20 }}>
                <h3 style={{ marginBottom: 12, fontSize: '0.95rem' }}>Comparação com o período anterior</h3>
                <p>{content.narrative.comparacaoTextual}</p>
              </div>
            )}
          </>
        )}
      </>
    );
  }

  return (
    <>
      <div className="toolbar">
        <div className="toolbar-left">
          <h2 style={{ margin: 0 }}>Relatórios de Performance</h2>
        </div>
      </div>

      {canWrite && (
        <div className="stat-card" style={{ padding: 16, marginBottom: 20 }}>
          <div className="form-grid" style={{ gridTemplateColumns: '1fr 1fr 1fr auto', gap: 12, alignItems: 'flex-end' }}>
            <div>
              <label className="form-label">Atendente</label>
              <input className="form-input" placeholder="ex: Luana Rangel" value={agentName} onChange={e => setAgentName(e.target.value)} />
            </div>
            <div>
              <label className="form-label">De</label>
              <input type="date" className="form-input" value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
            </div>
            <div>
              <label className="form-label">Até</label>
              <input type="date" className="form-input" value={dateTo} onChange={e => setDateTo(e.target.value)} />
            </div>
            <button className="btn btn-primary btn-sm" onClick={requestReport} disabled={requesting || cooldownActive}>
              {requesting ? 'Gerando…' : 'Gerar'}
            </button>
          </div>
          {cooldownActive && (
            <p className="td-muted" style={{ marginTop: 8, fontSize: '.8rem' }}>
              Cooldown ativo para este atendente até {new Date(nextAllowedAt!).toLocaleString('pt-BR')} (1 relatório a cada 5 dias úteis por atendente).
            </p>
          )}
          {formError && <div className="alert alert-error" style={{ marginTop: 8 }}>{formError}</div>}
        </div>
      )}

      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando relatórios…</div>
      ) : (
        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Atendente</th>
                <th>Período</th>
                {isAdmin && <th>Solicitante</th>}
                <th>Solicitado em</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {reports.length === 0 ? (
                <tr><td colSpan={isAdmin ? 5 : 4} className="table-empty">Nenhum relatório solicitado ainda</td></tr>
              ) : reports.map(r => (
                <tr key={r.id} onClick={() => openDetail(r.id)} style={{ cursor: 'pointer' }}>
                  <td>{r.agentName}</td>
                  <td className="td-muted">{r.dateFrom} a {r.dateTo}</td>
                  {isAdmin && <td className="td-muted">{r.requestedBy}</td>}
                  <td className="td-muted">{new Date(r.requestedAt).toLocaleString('pt-BR')}</td>
                  <td><span className="badge badge-info">{STATUS_LABELS[r.status] ?? r.status}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
          <button className="btn btn-ghost btn-sm" disabled={page === 0} onClick={() => load(page - 1)}>← Anterior</button>
          <span className="td-muted" style={{ alignSelf: 'center' }}>{page + 1} / {totalPages}</span>
          <button className="btn btn-ghost btn-sm" disabled={page >= totalPages - 1} onClick={() => load(page + 1)}>Próxima →</button>
        </div>
      )}
    </>
  );
}
