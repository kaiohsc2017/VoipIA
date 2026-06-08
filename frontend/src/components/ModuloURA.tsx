import { useEffect, useState } from 'react';
import api from '../api/client';
import type { CallRecord, UraQuestion, PageResponse } from '../api/types';

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

// ─── KPI cards do Módulo 1 ────────────────────────────────────────────────────

interface CallStats {
  totalCalls: number;
  callsWithJira: number;
  callsWithTranscription: number;
  jiraSuccessRatePct: number;
  avgDurationSecs: number;
}

function KpiBar() {
  const [stats, setStats] = useState<CallStats | null>(null);
  const [period, setPeriod] = useState<'today' | 'week' | 'month'>('today');

  const load = (p: typeof period) => {
    api.get<CallStats>(`/stats/calls?period=${p}`).then(r => setStats(r.data));
  };

  useEffect(() => { load('today'); }, []);

  const handlePeriod = (p: typeof period) => { setPeriod(p); load(p); };

  if (!stats) return null;

  const avgMin = stats.avgDurationSecs > 0
    ? `${Math.floor(stats.avgDurationSecs / 60)}m ${stats.avgDurationSecs % 60}s`
    : '—';

  return (
    <div style={{ marginBottom: 24 }}>
      <div className="flex gap-1" style={{ marginBottom: 12 }}>
        {(['today', 'week', 'month'] as const).map(p => (
          <button key={p} className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => handlePeriod(p)}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
          </button>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 12 }}>
        {[
          { label: 'Chamadas URA',     value: stats.totalCalls,             color: '#7c3aed' },
          { label: 'Chamados Jira',    value: stats.callsWithJira,          color: '#3b82f6' },
          { label: 'Taxa Jira',        value: `${stats.jiraSuccessRatePct}%`, color: '#68d391' },
          { label: 'Duração Média',    value: avgMin,                       color: '#f6ad55' },
        ].map(kpi => (
          <div key={kpi.label} className="stat-card" style={{ padding: '14px 18px' }}>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 4 }}>{kpi.label}</div>
            <div style={{ fontSize: '1.5rem', fontWeight: 700, color: kpi.color }}>{kpi.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Player de áudio inline ───────────────────────────────────────────────────

function AudioPlayer({ callId }: { callId: number }) {
  const [show, setShow] = useState(false);
  if (!show) {
    return (
      <button
        className="btn btn-ghost btn-sm btn-icon"
        onClick={() => setShow(true)}
        title="Ouvir gravação"
      >▶️</button>
    );
  }
  return (
    <audio
      controls
      autoPlay
      src={`/api/v1/calls/${callId}/audio`}
      style={{ height: 28, minWidth: 180, maxWidth: 240 }}
      onError={() => setShow(false)}
    />
  );
}

// ─── Módulo URA principal ─────────────────────────────────────────────────────

export default function ModuloURA() {
  const [tab, setTab] = useState<'calls' | 'questions'>('calls');
  const [calls, setCalls] = useState<CallRecord[]>([]);
  const [questions, setQuestions] = useState<UraQuestion[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [search, setSearch] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [editQ, setEditQ] = useState<Partial<UraQuestion>>({});

  const loadCalls = (p = 0) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '20' });
    if (search) params.set('callerNumber', search);
    api.get<PageResponse<CallRecord>>(`/calls?${params}`)
      .then(r => {
        setCalls(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .finally(() => setLoading(false));
  };

  const loadQuestions = () => {
    setLoading(true);
    api.get<UraQuestion[]>('/ura/questions/all')
      .then(r => setQuestions(r.data))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (tab === 'calls') loadCalls(0);
    else loadQuestions();
  }, [tab]);

  const handleSearchSubmit = (e: React.FormEvent) => { e.preventDefault(); loadCalls(0); };

  const toggleActive = async (q: UraQuestion) => {
    await api.patch(`/ura/questions/${q.id}/active?active=${!q.isActive}`);
    loadQuestions();
  };

  const openEditModal = (q?: UraQuestion) => {
    setEditQ(q ? { ...q } : { questionOrder: questions.length + 1, isActive: true });
    setShowModal(true);
  };

  const saveQuestion = async () => {
    if (editQ.id) { await api.put(`/ura/questions/${editQ.id}`, editQ); }
    else { await api.post('/ura/questions', editQ); }
    setShowModal(false);
    loadQuestions();
  };

  const deleteQuestion = async (id: number) => {
    if (confirm('Remover esta pergunta?')) { await api.delete(`/ura/questions/${id}`); loadQuestions(); }
  };

  return (
    <>
      <div className="page-header">
        <h1>🎫 Módulo 1 — URA / Jira</h1>
        <p>Histórico de chamadas da URA e configuração das perguntas</p>
      </div>
      <div className="page-body">

        {/* KPIs — sempre visíveis no topo */}
        <KpiBar />

        {/* Tabs */}
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20 }}>
          <button className={`btn ${tab === 'calls' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('calls')}>
            📋 Chamadas
          </button>
          <button className={`btn ${tab === 'questions' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('questions')}>
            ❓ Perguntas URA
          </button>
        </div>

        {/* ---- CALLS TAB ---- */}
        {tab === 'calls' && (
          <>
            <div className="toolbar">
              <form className="toolbar-left" onSubmit={handleSearchSubmit}>
                <div className="search-wrapper">
                  <span className="search-icon">🔍</span>
                  <input className="search-input" placeholder="Filtrar por número..."
                    value={search} onChange={e => setSearch(e.target.value)} />
                </div>
                <button type="submit" className="btn btn-ghost btn-sm">Buscar</button>
              </form>
            </div>

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
                      <th>Chamado Jira</th>
                      <th>Status</th>
                      <th>Duração</th>
                      <th>Áudio</th>
                      <th>Transcrição</th>
                    </tr>
                  </thead>
                  <tbody>
                    {calls.length === 0 ? (
                      <tr><td colSpan={9} className="table-empty">Nenhuma chamada registrada</td></tr>
                    ) : calls.map(c => (
                      <tr key={c.id}>
                        <td className="td-muted">{c.id}</td>
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
                        <td>
                          {c.audioFilePath
                            ? <AudioPlayer callId={c.id} />
                            : <span className="text-muted">—</span>}
                        </td>
                        <td style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {c.transcription
                            ? <span title={c.transcription} className="td-muted">{c.transcription}</span>
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

        {/* ---- QUESTIONS TAB ---- */}
        {tab === 'questions' && (
          <>
            <div className="toolbar">
              <div className="toolbar-left" />
              <div className="toolbar-right">
                <button className="btn btn-primary" onClick={() => openEditModal()}>＋ Nova Pergunta</button>
              </div>
            </div>

            {loading ? (
              <div className="loading-state"><div className="spinner" />Carregando perguntas…</div>
            ) : (
              <div className="table-wrapper">
                <table>
                  <thead>
                    <tr>
                      <th>Ordem</th>
                      <th>Pergunta</th>
                      <th>Campo Jira</th>
                      <th>Valores Válidos</th>
                      <th>Status</th>
                      <th>Ações</th>
                    </tr>
                  </thead>
                  <tbody>
                    {questions.length === 0 ? (
                      <tr><td colSpan={6} className="table-empty">Nenhuma pergunta cadastrada</td></tr>
                    ) : questions
                      .sort((a, b) => a.questionOrder - b.questionOrder)
                      .map(q => (
                        <tr key={q.id}>
                          <td style={{ textAlign: 'center', fontWeight: 700 }}>{q.questionOrder}</td>
                          <td style={{ maxWidth: 320 }}>{q.questionText}</td>
                          <td><span className="chip mono">{q.jiraFieldKey}</span></td>
                          <td className="td-muted">{q.expectedValues || '—'}</td>
                          <td>
                            <span className={`badge ${q.isActive ? 'badge-success' : 'badge-gray'}`}>
                              {q.isActive ? 'Ativa' : 'Inativa'}
                            </span>
                          </td>
                          <td>
                            <div className="flex gap-1">
                              <button className="btn btn-ghost btn-sm btn-icon" onClick={() => openEditModal(q)} title="Editar">✏️</button>
                              <button className="btn btn-ghost btn-sm btn-icon" onClick={() => toggleActive(q)} title={q.isActive ? 'Desativar' : 'Ativar'}>
                                {q.isActive ? '⏸' : '▶️'}
                              </button>
                              <button className="btn btn-danger btn-sm btn-icon" onClick={() => deleteQuestion(q.id)} title="Remover">🗑️</button>
                            </div>
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>

      {/* Modal edição de pergunta */}
      {showModal && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal">
            <div className="modal-header">
              <h2>❓ {editQ.id ? 'Editar' : 'Nova'} Pergunta URA</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Ordem</label>
                <input type="number" className="form-input" value={editQ.questionOrder ?? ''}
                  onChange={e => setEditQ(q => ({ ...q, questionOrder: +e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Texto da Pergunta (TTS)</label>
                <textarea className="form-textarea" value={editQ.questionText ?? ''}
                  onChange={e => setEditQ(q => ({ ...q, questionText: e.target.value }))}
                  placeholder="Qual é o seu nome completo?" />
              </div>
              <div className="form-group">
                <label className="form-label">Campo do Jira (field key)</label>
                <input type="text" className="form-input" value={editQ.jiraFieldKey ?? ''}
                  onChange={e => setEditQ(q => ({ ...q, jiraFieldKey: e.target.value }))}
                  placeholder="customfield_nome_cliente" />
              </div>
              <div className="form-group">
                <label className="form-label">Valores Válidos (separados por vírgula)</label>
                <input type="text" className="form-input" value={editQ.expectedValues ?? ''}
                  onChange={e => setEditQ(q => ({ ...q, expectedValues: e.target.value }))}
                  placeholder="Baixa,Média,Alta (deixe em branco para livre)" />
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={saveQuestion}>
                {editQ.id ? 'Salvar Alterações' : 'Criar Pergunta'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
