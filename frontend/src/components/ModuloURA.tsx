import { useEffect, useState } from 'react';
import api from '../api/client';
import type { CallRecord, PageResponse, Ura } from '../api/types';
import UraManagementTab from './UraManagementTab';
import { AuthedAudio } from './AuthedAudio';
import { KpiBar } from './KpiBar';
import { AudioPlayer } from './AudioPlayer';
import { DashboardTab } from './DashboardTab';
import { RankingTab, type RankingDrillDownFilters } from './RankingTab';

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

// ─── Módulo URA principal ────────────────────────────────────────────────────

export default function ModuloURA() {
  const [tab, setTab] = useState<'calls' | 'dashboard' | 'uras' | 'ranking'>('calls');
  const [uras, setUras] = useState<Ura[]>([]);
  const [calls, setCalls] = useState<CallRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [search, setSearch] = useState('');
  const [exporting, setExporting] = useState(false);
  const [detailCall, setDetailCall] = useState<CallRecord | null>(null);

  // Filtros avançados (colapsáveis)
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [clientNameFilter, setClientNameFilter] = useState('');
  const [ramalFilter, setRamalFilter] = useState('');
  const [callTypeFilter, setCallTypeFilter] = useState('');
  const [jiraKeyFilter, setJiraKeyFilter] = useState('');
  const [transcriptionFilter, setTranscriptionFilter] = useState('');
  const [priorityFilter, setPriorityFilter] = useState('');
  const [uraFilter, setUraFilter] = useState('');
  const [subjectTagFilter, setSubjectTagFilter] = useState('');
  const [jiraResolutionFilter, setJiraResolutionFilter] = useState('');

  const hasActiveFilters = !!(dateFrom || dateTo || clientNameFilter || ramalFilter
    || callTypeFilter || jiraKeyFilter || transcriptionFilter || priorityFilter || uraFilter
    || subjectTagFilter || jiraResolutionFilter);

  const loadCalls = (p = 0) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '20' });
    if (search) params.set('callerNumber', search);
    if (dateFrom) params.set('dateFrom', dateFrom);
    if (dateTo) params.set('dateTo', dateTo);
    if (clientNameFilter) params.set('clientName', clientNameFilter);
    if (ramalFilter) params.set('ramal', ramalFilter);
    if (callTypeFilter) params.set('callType', callTypeFilter);
    if (jiraKeyFilter) params.set('jiraIssueKey', jiraKeyFilter);
    if (transcriptionFilter) params.set('transcriptionText', transcriptionFilter);
    if (priorityFilter) params.set('priority', priorityFilter);
    if (uraFilter) params.set('uraId', uraFilter);
    if (subjectTagFilter) params.set('subjectTag', subjectTagFilter);
    if (jiraResolutionFilter) params.set('jiraResolution', jiraResolutionFilter);
    api.get<PageResponse<CallRecord>>(`/calls?${params}`)
      .then(r => {
        setCalls(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .catch(err => {
        console.error('Erro ao carregar chamadas:', err);
        setCalls([]);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    api.get<Ura[]>('/uras').then(r => setUras(r.data))
      .catch(err => console.error('Erro ao carregar URAs:', err));
  }, []);

  useEffect(() => {
    if (tab === 'calls') loadCalls(0);
    // Busca é sob demanda (botão "Buscar" chama loadCalls diretamente) — incluir
    // os filtros aqui disparia uma requisição a cada tecla digitada.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const handleSearchSubmit = (e: React.FormEvent) => { e.preventDefault(); loadCalls(0); };

  const clearFilters = () => {
    setDateFrom(''); setDateTo(''); setClientNameFilter(''); setRamalFilter('');
    setCallTypeFilter(''); setJiraKeyFilter(''); setTranscriptionFilter(''); setPriorityFilter(''); setUraFilter('');
    setSubjectTagFilter(''); setJiraResolutionFilter('');
    // Recarrega já sem os filtros — precisa esperar o próximo tick para os estados aplicarem
    setTimeout(() => loadCalls(0), 0);
  };

  /**
   * Drill-down vindo do Ranking de Atendimentos ou do Dashboard: troca para a aba
   * Chamadas já com o filtro correspondente ao ponto clicado (barra/linha do
   * Ranking, ou dia do gráfico do Dashboard) — os demais filtros avançados são
   * limpos para não combinar com um recorte anterior sem o usuário perceber.
   */
  const handleDrillDown = (filters: RankingDrillDownFilters) => {
    setRamalFilter(''); setJiraKeyFilter('');
    setTranscriptionFilter(''); setPriorityFilter(''); setUraFilter('');
    setClientNameFilter(filters.clientName ?? '');
    setCallTypeFilter(filters.callType ?? '');
    setSubjectTagFilter(filters.subjectTag ?? '');
    setJiraResolutionFilter(filters.jiraResolution ?? '');
    setDateFrom(filters.dateFrom ?? '');
    setDateTo(filters.dateTo ?? '');
    setFiltersOpen(true);
    setTab('calls');
  };

  const priorityBadge = (value?: string) => {
    if (!value) return <span className="text-muted">—</span>;
    const v = value.toLowerCase();
    const cls = v.includes('alta') ? 'badge-danger' : v.includes('méd') || v.includes('med') ? 'badge-warning'
      : v.includes('baix') ? 'badge-success' : 'badge-gray';
    return <span className={`badge ${cls}`}>{value}</span>;
  };

  const exportUra = async () => {
    setExporting(true);
    try {
      const response = await api.get(`/calls/export`, { responseType: 'blob' });
      const url  = URL.createObjectURL(new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `chamadas_ura.xlsx`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch {
      alert('Erro ao exportar chamadas. Tente novamente.');
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <div className="page-header">
        <h1>🎫 URA</h1>
        <p>Histórico de chamadas e configuração do fluxo de atendimento</p>
      </div>
      <div className="page-body">

        <KpiBar />

        {/* Tabs */}
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20, display: 'flex', gap: 6 }}>
          <button className={`btn ${tab === 'uras'      ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('uras')}>
            🎛️ URAs
          </button>
          <button className={`btn ${tab === 'dashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('dashboard')}>
            📊 Dashboard
          </button>
          <button className={`btn ${tab === 'calls'     ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('calls')}>
            📋 Chamadas
          </button>
          <button className={`btn ${tab === 'ranking'   ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('ranking')}>
            🏆 Ranking de Atendimentos
          </button>
        </div>

        {/* ---- CALLS TAB ---- */}
      {/* Modal detalhe da chamada */}
      {detailCall && (
        <div className="modal-overlay" onClick={() => setDetailCall(null)}>
          <div className="modal" style={{ maxWidth: 640, width: '96vw' }} onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h3 style={{ fontSize: '1rem', fontWeight: 600 }}>
                Chamada #{detailCall.id} — {formatDate(detailCall.callDate)}
              </h3>
              <button className="btn-close" onClick={() => setDetailCall(null)}>×</button>
            </div>
            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

              {/* Info básica */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Número</div>
                  <div className="mono" style={{ fontSize: '.9rem' }}>{detailCall.callerNumber}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Status</div>
                  <span className="badge badge-info">{detailCall.jiraIssueStatus || 'Aberto'}</span>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Cliente</div>
                  <div style={{ fontSize: '.9rem' }}>{detailCall.clientName || detailCall.callerNumber || '—'}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Tipo</div>
                  <div style={{ fontSize: '.9rem' }}>{detailCall.callType || '—'}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Ramal informado</div>
                  <div className="mono" style={{ fontSize: '.9rem' }}>{detailCall.reportedRamal || '—'}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Impacto</div>
                  <div style={{ fontSize: '.9rem' }}>{priorityBadge(detailCall.priority)}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Chamado Jira</div>
                  <div style={{ fontSize: '.9rem' }}>{detailCall.jiraIssueKey
                    ? <span className="chip">{detailCall.jiraIssueKey}</span>
                    : '—'}
                  </div>
                </div>
              </div>

              {/* Player de áudio */}
              <div>
                <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Gravação da chamada</div>
                {detailCall.audioFilePath ? (
                  <AuthedAudio path={`/calls/${detailCall.id}/audio`} style={{ width: '100%', height: 36 }} />
                ) : (
                  <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>Gravação não disponível</span>
                )}
              </div>

              {/* Respostas por pergunta */}
              {detailCall.answers && detailCall.answers.length > 0 && (
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Respostas por pergunta</div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                    {detailCall.answers.map(a => (
                      <div key={a.questionId} style={{
                        background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                        borderRadius: 8, padding: '8px 12px',
                      }}>
                        <div style={{ fontSize: '.7rem', color: 'var(--text-muted)', marginBottom: 3 }}>{a.questionText}</div>
                        <div style={{ fontSize: '.85rem', fontWeight: 500 }}>{a.value || '—'}</div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Transcrição completa */}
              <div>
                <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Transcrição completa</div>
                {detailCall.transcription ? (
                  <div style={{
                    background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                    borderRadius: 8, padding: '12px 14px',
                    maxHeight: 280, overflowY: 'auto',
                    display: 'flex', flexDirection: 'column', gap: 12,
                  }}>
                    {detailCall.transcription.split('\n').filter(l => l.trim()).map((line, i) => {
                      // Formato salvo pelo ai-agent: [pergunta da URA]: resposta do cliente
                      const match = line.match(/^\[(.+?)\]:\s*(.*)$/);
                      if (!match) {
                        return (
                          <div key={i} style={{ fontSize: '.82rem', color: 'var(--text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                            {line}
                          </div>
                        );
                      }
                      const [, pergunta, resposta] = match;
                      return (
                        <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                            <span className="badge badge-info" style={{ fontSize: '.62rem', flexShrink: 0, marginTop: 1 }}>URA</span>
                            <span style={{ fontSize: '.8rem', color: 'var(--text-muted)', fontStyle: 'italic', wordBreak: 'break-word' }}>{pergunta}</span>
                          </div>
                          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, paddingLeft: 6 }}>
                            <span className="badge badge-success" style={{ fontSize: '.62rem', flexShrink: 0, marginTop: 1 }}>Cliente</span>
                            <span style={{ fontSize: '.85rem', color: 'var(--text-primary)', fontWeight: 500, wordBreak: 'break-word' }}>{resposta || '—'}</span>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>Transcrição não disponível</span>
                )}
              </div>

            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setDetailCall(null)}>Fechar</button>
            </div>
          </div>
        </div>
      )}


        {tab === 'calls' && (
          <>
            <div className="toolbar">
              <form className="toolbar-left" onSubmit={handleSearchSubmit}>
                <div className="search-wrapper">
                  <span className="search-icon">🔍</span>
                  <input className="search-input" aria-label="Filtrar por número" placeholder="Filtrar por número..."
                    value={search} onChange={e => setSearch(e.target.value)} />
                </div>
                <button type="submit" className="btn btn-ghost btn-sm">Buscar</button>
                <button
                  type="button"
                  className={`btn btn-sm ${hasActiveFilters ? 'btn-primary' : 'btn-ghost'}`}
                  onClick={() => setFiltersOpen(o => !o)}
                >
                  🔧 Filtros{hasActiveFilters ? ' •' : ''}
                </button>
              </form>
              <div className="toolbar-right">
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={exportUra}
                  disabled={exporting}
                  style={{ borderColor: 'rgba(0,122,255,0.4)', color: '#4da8ff', minWidth: 140 }}
                >
                  {exporting
                    ? <><span className="spinner" style={{ width: 12, height: 12, margin: '0 6px 0 0' }} />Exportando…</>
                    : '⬇ Exportar CSV'}
                </button>
              </div>
            </div>

            {filtersOpen && (
              <div className="form-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
                <div>
                  <label className="form-label">URA</label>
                  <select className="form-select" value={uraFilter} onChange={e => setUraFilter(e.target.value)}>
                    <option value="">Todas as URAs</option>
                    {uras.map(u => (
                      <option key={u.id} value={u.id}>{u.name} (ramal {u.extension})</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="form-label">Data de</label>
                  <input type="date" className="form-input" value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Data até</label>
                  <input type="date" className="form-input" value={dateTo} onChange={e => setDateTo(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Login / Cliente</label>
                  <input className="form-input" placeholder="ex: kaio.correa" value={clientNameFilter} onChange={e => setClientNameFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Ramal informado</label>
                  <input className="form-input" placeholder="ex: 5004" value={ramalFilter} onChange={e => setRamalFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Tipo</label>
                  <select className="form-select" value={callTypeFilter} onChange={e => setCallTypeFilter(e.target.value)}>
                    <option value="">Todos</option>
                    <option value="Incidente">Incidente</option>
                    <option value="Requisição">Requisição</option>
                  </select>
                </div>
                <div>
                  <label className="form-label">Chamado Jira</label>
                  <input className="form-input" placeholder="ex: SUPP-123" value={jiraKeyFilter} onChange={e => setJiraKeyFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Impacto</label>
                  <select className="form-select" value={priorityFilter} onChange={e => setPriorityFilter(e.target.value)}>
                    <option value="">Todos</option>
                    <option value="Baixa">Baixa</option>
                    <option value="Média">Média</option>
                    <option value="Alta">Alta</option>
                  </select>
                </div>
                <div>
                  <label className="form-label">Texto na transcrição</label>
                  <input className="form-input" placeholder="ex: computador reiniciando" value={transcriptionFilter} onChange={e => setTranscriptionFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Assunto (IA)</label>
                  <input className="form-input" placeholder="ex: Reset de senha" value={subjectTagFilter} onChange={e => setSubjectTagFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Solução (Jira)</label>
                  <input className="form-input" placeholder="ex: Resolvido" value={jiraResolutionFilter} onChange={e => setJiraResolutionFilter(e.target.value)} />
                </div>
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8 }}>
                  <button className="btn btn-primary btn-sm" onClick={() => loadCalls(0)}>Aplicar filtros</button>
                  <button className="btn btn-ghost btn-sm" onClick={clearFilters} disabled={!hasActiveFilters}>Limpar</button>
                </div>
              </div>
            )}

            {loading ? (
              <div className="loading-state"><div className="spinner" />Carregando chamadas…</div>
            ) : (
              <div className="table-wrapper">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Data / Hora</th>
                      <th>Número</th>
                      <th>Cliente</th>
                      <th>Tipo</th>
                      <th>Impacto</th>
                      <th>Chamado Jira</th>
                      <th>Status</th>
                      <th>Duração</th>
                      <th>Áudio</th>
                      <th>Transcrição</th>
                    </tr>
                  </thead>
                  <tbody>
                    {calls.length === 0 ? (
                      <tr><td colSpan={11} className="table-empty">Nenhuma chamada registrada</td></tr>
                    ) : calls.map(c => (
                      <tr key={c.id}
                        onClick={() => setDetailCall(c)}
                        style={{ cursor: 'pointer' }}
                        title="Clique para ver detalhes"
                      >
                        <td className="td-muted">{c.id}</td>
                        <td className="td-muted">{formatDate(c.callDate)}</td>
                        <td className="mono">{c.callerNumber}</td>
                        <td>{c.clientName || c.callerNumber || <span className="text-muted">—</span>}</td>
                        <td>
                          {c.callType
                            ? <span className="badge" style={{ background: c.callType.toLowerCase().includes('incidente') ? 'rgba(255,107,107,0.1)' : 'rgba(0,122,255,0.1)', color: c.callType.toLowerCase().includes('incidente') ? '#b3342f' : '#4da8ff' }}>{c.callType}</span>
                            : <span className="text-muted">—</span>}
                        </td>
                        <td>{priorityBadge(c.priority)}</td>
                        <td>
                          {c.jiraIssueKey
                            ? <span className="chip">{c.jiraIssueKey}</span>
                            : <span className="text-muted">—</span>}
                        </td>
                        <td><span className="badge badge-info">{c.jiraIssueStatus || 'Aberto'}</span></td>
                        <td className="td-muted">{c.callDurationSecs}s</td>
                        <td onClick={e => e.stopPropagation()}>
                          {c.audioFilePath
                            ? <AudioPlayer callId={c.id} />
                            : <span className="text-muted">—</span>}
                        </td>
                        <td className="td-muted" style={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {c.transcription
                            ? <span style={{ opacity: .7 }}>{c.transcription.slice(0, 60)}{c.transcription.length > 60 ? '…' : ''}</span>
                            : <span className="text-muted">—</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="pagination">
                  <span className="pagination-info">{calls.length} registros nesta página</span>
                  <div className="pagination-btns">
                    <button className="page-btn" disabled={page === 0} onClick={() => loadCalls(page - 1)}>‹</button>
                    {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => (
                      <button key={i} className={`page-btn ${i === page ? 'active' : ''}`} onClick={() => loadCalls(i)}>
                        {i + 1}
                      </button>
                    ))}
                    <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => loadCalls(page + 1)}>›</button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {/* ---- DASHBOARD TAB ---- */}
        {tab === 'dashboard' && <DashboardTab onDrillDown={handleDrillDown} />}

        {/* ---- RANKING TAB ---- */}
        {tab === 'ranking' && <RankingTab uras={uras} onDrillDown={handleDrillDown} />}

        {/* ---- URAs TAB ---- */}
        {tab === 'uras' && <UraManagementTab />}

      </div>
    </>
  );
}
