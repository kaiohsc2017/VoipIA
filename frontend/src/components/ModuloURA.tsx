import { useEffect, useState, useCallback } from 'react';
import { subscribe } from '../api/websocket';
import api from '../api/client';
import type { CallRecord, UraQuestion, PageResponse } from '../api/types';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

// ─── URA Settings types ──────────────────────────────────────────────────────

interface UraSetting {
  key: string;
  label: string;
  value: string;
  required: boolean;
}

// ─── Dashboard com gráfico temporal ─────────────────────────────────────────

interface TimePoint { date: string; total: number; jiraOpened: number; avgDuration: number; }

function DashboardTab() {
  const [series, setSeries] = useState<TimePoint[]>([]);
  const [period, setPeriod] = useState<'week' | 'month'>('week');
  const [loading, setLoading] = useState(true);

  const load = useCallback((p: 'week' | 'month') => {
    setLoading(true);
    api.get<TimePoint[]>(`/stats/calls/timeseries?period=${p}`)
      .then(r => setSeries(r.data))
      .finally(() => setLoading(false));
  }, []);

  // Carrega na montagem e se inscreve no WebSocket para atualizar em tempo real
  useEffect(() => {
    load('week');
    const unsub = subscribe('/topic/calls', () => {
      // Nova chamada registrada — recarrega o gráfico sem trocar o período
      load(period);
    });
    return () => unsub?.();
  }, []);  // eslint-disable-line react-hooks/exhaustive-deps

  const formatDateLocal = (d: string) => {
    if (!d) return '';
    const dt = new Date(d);
    return `${String(dt.getDate()).padStart(2,'0')}/${String(dt.getMonth()+1).padStart(2,'0')}`;
  };

  const chartData = series.map(p => ({
    ...p,
    date: formatDateLocal(p.date),
    Chamadas: p.total,
    'Jira Abertas': p.jiraOpened,
  }));

  return (
    <div>
      <div className="flex gap-1" style={{ marginBottom: 16 }}>
        {(['week', 'month'] as const).map(p => (
          <button key={p}
            className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => { setPeriod(p); load(p); }}>
            {p === 'week' ? 'Últimos 7 dias' : 'Últimos 30 dias'}
          </button>
        ))}
      </div>
      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando gráfico…</div>
      ) : series.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
          Sem chamadas no período selecionado
        </div>
      ) : (
        <div className="stat-card" style={{ padding: 20 }}>
          <h3 style={{ marginBottom: 16, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
            📊 Chamadas URA por dia
          </h3>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={chartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
              <XAxis dataKey="date" tick={{ fill: '#94a3b8', fontSize: 12 }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} allowDecimals={false} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: '1px solid rgba(148,163,184,0.15)', borderRadius: 8 }}
                labelStyle={{ color: '#e2e8f0' }}
              />
              <Legend wrapperStyle={{ fontSize: 12, color: '#94a3b8' }} />
              <Bar dataKey="Chamadas" fill="#7c3aed" radius={[4,4,0,0]} />
              <Bar dataKey="Jira Abertas" fill="#3b82f6" radius={[4,4,0,0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

// ─── KPI cards ───────────────────────────────────────────────────────────────

interface CallStats {
  totalCalls: number;
  callsWithJira: number;
  jiraSuccessRatePct: number;
  avgDurationSecs: number;
}

function KpiBar() {
  const [stats, setStats] = useState<CallStats | null>(null);
  const [period, setPeriod] = useState<'today' | 'week' | 'month'>('today');

  const load = useCallback((p: typeof period) => {
    api.get<CallStats>(`/stats/calls?period=${p}`).then(r => setStats(r.data));
  }, []);

  useEffect(() => {
    load('today');
    const unsub = subscribe('/topic/calls', () => load(period));
    return () => unsub?.();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (!stats) return null;

  const avgMin = stats.avgDurationSecs > 0
    ? `${Math.floor(stats.avgDurationSecs / 60)}m ${stats.avgDurationSecs % 60}s`
    : '—';

  return (
    <div style={{ marginBottom: 16 }}>
      <div className="flex gap-1" style={{ marginBottom: 10 }}>
        {(['today', 'week', 'month'] as const).map(p => (
          <button key={p} className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => { setPeriod(p); load(p); }}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
          </button>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 10 }}>
        {[
          { label: 'Chamadas URA',  value: stats.totalCalls,               color: '#7c3aed' },
          { label: 'Chamados Jira', value: stats.callsWithJira,            color: '#3b82f6' },
          { label: 'Taxa Jira',     value: `${stats.jiraSuccessRatePct}%`, color: '#68d391' },
          { label: 'Duração Média', value: avgMin,                         color: '#f6ad55' },
        ].map(kpi => (
          <div key={kpi.label} className="stat-card" style={{ padding: '12px 16px' }}>
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>{kpi.label}</div>
            <div style={{ fontSize: '1.4rem', fontWeight: 700, color: kpi.color }}>{kpi.value}</div>
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

// ─── Aba Fluxo URA ───────────────────────────────────────────────────────────

function FluxoURATab() {
  const [settings, setSettings]       = useState<UraSetting[]>([]);
  const [questions, setQuestions]     = useState<UraQuestion[]>([]);
  const [loadingData, setLoadingData] = useState(true);
  const [saving, setSaving]           = useState<string | null>(null);
  const [saved, setSaved]             = useState<string | null>(null);
  const [localValues, setLocalValues] = useState<Record<string, string>>({});

  // Modal pergunta
  const [showModal, setShowModal] = useState(false);
  // Modal detalhe da chamada
  const [detailCall, setDetailCall] = useState<CallRecord | null>(null);
  const [editQ, setEditQ]         = useState<Partial<UraQuestion>>({});

  const load = async () => {
    setLoadingData(true);
    const [s, q] = await Promise.all([
      api.get<UraSetting[]>('/ura/settings'),
      api.get<UraQuestion[]>('/ura/questions/all'),
    ]);
    setSettings(s.data);
    setLocalValues(Object.fromEntries(s.data.map(x => [x.key, x.value])));
    setQuestions(q.data);
    setLoadingData(false);
  };

  useEffect(() => { load(); }, []);

  const saveSetting = async (key: string) => {
    setSaving(key);
    try {
      await api.put(`/ura/settings/${key}`, { value: localValues[key] ?? '' });
      setSaved(key);
      setTimeout(() => setSaved(null), 2000);
      await load();
    } catch (err: any) {
      alert(err.response?.data?.message ?? 'Erro ao salvar. Tente novamente.');
    } finally {
      setSaving(null);
    }
  };

  const toggleActive = async (q: UraQuestion) => {
    await api.patch(`/ura/questions/${q.id}/active?active=${!q.isActive}`);
    load();
  };

  const openEditModal = (q?: UraQuestion) => {
    setEditQ(q ? { ...q } : { questionOrder: questions.length + 1, isActive: true });
    setShowModal(true);
  };

  const saveQuestion = async () => {
    if (!editQ.questionText?.trim())  { alert('Informe o texto da pergunta.'); return; }
    if (!editQ.jiraFieldKey?.trim())  { alert('Informe o campo do Jira.');     return; }
    if (!editQ.questionOrder)         { alert('Informe a ordem.');             return; }
    if (editQ.id) { await api.put(`/ura/questions/${editQ.id}`, editQ); }
    else          { await api.post('/ura/questions', editQ); }
    setShowModal(false);
    load();
  };

  const deleteQuestion = async (id: number) => {
    if (confirm('Remover esta pergunta?')) { await api.delete(`/ura/questions/${id}`); load(); }
  };

  // Helpers para renderizar cada bloco de mensagem
  const settingMeta: Record<string, { icon: string; color: string; badge: string; hint?: string }> = {
    boas_vindas:  { icon: '👋', color: 'var(--color-background-info)',    badge: 'Obrigatória' },
    informativa:  { icon: 'ℹ️',  color: 'var(--color-background-secondary)', badge: 'Opcional',
                    hint: 'Deixe em branco para não reproduzir' },
    encerramento: { icon: '✅', color: 'var(--color-background-info)',    badge: 'Obrigatória',
                    hint: 'Use {protocolo} para incluir o número do chamado gerado' },
  };

  if (loadingData) {
    return <div className="loading-state"><div className="spinner" />Carregando fluxo da URA…</div>;
  }

  const FLOW_ORDER = ['boas_vindas', 'informativa', 'encerramento'];
  const settingsByKey = Object.fromEntries(settings.map(s => [s.key, s]));

  return (
    <>
      {/* Indicador do fluxo */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 0, marginBottom: 16, fontSize: 12 }}>
        {[
          { label: 'Boas-vindas',               color: 'var(--color-background-info)',    border: 'var(--color-border-info)'    },
          { label: 'Informativa (opcional)',     color: 'var(--color-background-secondary)', border: 'var(--color-border-secondary)' },
          { label: 'Perguntas (1, 2, 3…)',       color: 'var(--color-background-secondary)', border: 'var(--color-border-secondary)' },
          { label: 'Encerramento',               color: 'var(--color-background-info)',    border: 'var(--color-border-info)'    },
        ].map((step, i, arr) => (
          <div key={step.label} style={{ display: 'flex', alignItems: 'center' }}>
            <div style={{
              padding: '4px 12px',
              background: step.color,
              border: `0.5px solid ${step.border}`,
              borderRadius: i === 0 ? '6px 0 0 6px' : i === arr.length - 1 ? '0 6px 6px 0' : 0,
              color: 'var(--color-text-secondary)',
              fontWeight: 500,
              whiteSpace: 'nowrap',
            }}>{step.label}</div>
            {i < arr.length - 1 && (
              <div style={{ padding: '4px 4px', background: 'var(--color-background-secondary)', border: '0.5px solid var(--color-border-tertiary)', borderLeft: 'none', borderRight: 'none', color: 'var(--color-text-secondary)' }}>›</div>
            )}
          </div>
        ))}
      </div>

      <div className="table-wrapper" style={{ padding: 0, overflow: 'visible' }}>

        {/* ── Mensagens de boas-vindas e informativa ── */}
        {FLOW_ORDER.filter(k => k !== 'encerramento').map(key => {
          const s = settingsByKey[key];
          if (!s) return null;
          const meta = settingMeta[key];
          const isDirty = localValues[key] !== s.value;
          return (
            <div key={key} style={{ padding: '18px 20px', borderBottom: '0.5px solid var(--color-border-tertiary)' }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div style={{
                    width: 30, height: 30, borderRadius: '50%', background: meta.color,
                    border: '0.5px solid var(--color-border-tertiary)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, flexShrink: 0,
                  }}>{meta.icon}</div>
                  <div>
                    <div style={{ fontWeight: 500, fontSize: 13 }}>{s.label}</div>
                    {meta.hint && (
                      <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 2 }}>{meta.hint}</div>
                    )}
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className={`badge ${s.required ? 'badge-info' : 'badge-gray'}`} style={{ fontSize: 10 }}>
                    {meta.badge}
                  </span>
                  <button
                    className="btn btn-primary btn-sm"
                    onClick={() => saveSetting(key)}
                    disabled={saving === key || !isDirty}
                    style={{ minWidth: 80, fontSize: 12 }}
                  >
                    {saving === key ? <><span className="spinner" style={{ width: 10, height: 10, margin: '0 4px 0 0' }} />Salvando…</> :
                     saved  === key ? '✓ Salvo' : 'Salvar'}
                  </button>
                </div>
              </div>
              <textarea
                className="form-textarea"
                value={localValues[key] ?? ''}
                onChange={e => setLocalValues(v => ({ ...v, [key]: e.target.value }))}
                placeholder={s.required ? 'Digite a mensagem…' : 'Deixe em branco para não reproduzir esta mensagem'}
                style={{ minHeight: key === 'informativa' ? 60 : 80 }}
              />
            </div>
          );
        })}

        {/* ── Divisor perguntas ── */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 12,
          padding: '10px 20px',
          background: 'var(--color-background-secondary)',
          borderTop: '0.5px solid var(--color-border-tertiary)',
          borderBottom: '0.5px solid var(--color-border-tertiary)',
        }}>
          <div style={{ flex: 1, height: '0.5px', background: 'var(--color-border-tertiary)' }} />
          <span style={{ fontSize: 11, fontWeight: 500, color: 'var(--color-text-secondary)', textTransform: 'uppercase', letterSpacing: '.06em', whiteSpace: 'nowrap' }}>
            ❓ Perguntas da URA
          </span>
          <div style={{ flex: 1, height: '0.5px', background: 'var(--color-border-tertiary)' }} />
        </div>

        {/* ── Toolbar perguntas ── */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 20px', borderBottom: '0.5px solid var(--color-border-tertiary)' }}>
          <span style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>
            Executadas em ordem numérica durante a chamada
          </span>
          <button className="btn btn-primary btn-sm" onClick={() => openEditModal()}>＋ Nova Pergunta</button>
        </div>

        {/* ── Tabela de perguntas ── */}
        <table>
          <thead>
            <tr>
              <th>Ordem</th>
              <th>Texto da pergunta (TTS)</th>
              <th>Campo Jira</th>
              <th>Valores válidos</th>
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

        {/* ── Divisor encerramento ── */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 12,
          padding: '10px 20px',
          background: 'var(--color-background-secondary)',
          borderTop: '0.5px solid var(--color-border-tertiary)',
          borderBottom: '0.5px solid var(--color-border-tertiary)',
        }}>
          <div style={{ flex: 1, height: '0.5px', background: 'var(--color-border-tertiary)' }} />
          <span style={{ fontSize: 11, fontWeight: 500, color: 'var(--color-text-secondary)', textTransform: 'uppercase', letterSpacing: '.06em', whiteSpace: 'nowrap' }}>
            ✅ Após todas as perguntas
          </span>
          <div style={{ flex: 1, height: '0.5px', background: 'var(--color-border-tertiary)' }} />
        </div>

        {/* ── Mensagem de encerramento ── */}
        {(() => {
          const s = settingsByKey['encerramento'];
          if (!s) return null;
          const meta = settingMeta['encerramento'];
          const isDirty = localValues['encerramento'] !== s.value;
          return (
            <div style={{ padding: '18px 20px' }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div style={{
                    width: 30, height: 30, borderRadius: '50%', background: meta.color,
                    border: '0.5px solid var(--color-border-tertiary)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, flexShrink: 0,
                  }}>{meta.icon}</div>
                  <div>
                    <div style={{ fontWeight: 500, fontSize: 13 }}>{s.label}</div>
                    {meta.hint && (
                      <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 2 }}>{meta.hint}</div>
                    )}
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className="badge badge-info" style={{ fontSize: 10 }}>{meta.badge}</span>
                  <button
                    className="btn btn-primary btn-sm"
                    onClick={() => saveSetting('encerramento')}
                    disabled={saving === 'encerramento' || !isDirty}
                    style={{ minWidth: 80, fontSize: 12 }}
                  >
                    {saving === 'encerramento' ? <><span className="spinner" style={{ width: 10, height: 10, margin: '0 4px 0 0' }} />Salvando…</> :
                     saved  === 'encerramento' ? '✓ Salvo' : 'Salvar'}
                  </button>
                </div>
              </div>
              <textarea
                className="form-textarea"
                value={localValues['encerramento'] ?? ''}
                onChange={e => setLocalValues(v => ({ ...v, encerramento: e.target.value }))}
                style={{ minHeight: 80 }}
              />
            </div>
          );
        })()}
      </div>

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
                  <div style={{ fontSize: '.9rem' }}>{detailCall.clientName || '—'}</div>
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
                  <audio
                    controls
                    src={`/api/v1/calls/${detailCall.id}/audio`}
                    style={{ width: '100%', height: 36 }}
                    onError={e => { (e.target as HTMLAudioElement).style.display = 'none'; }}
                  />
                ) : (
                  <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>Gravação não disponível</span>
                )}
              </div>

              {/* Transcrição completa */}
              <div>
                <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Transcrição completa</div>
                {detailCall.transcription ? (
                  <pre style={{
                    background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                    borderRadius: 8, padding: '12px 14px', fontSize: '.82rem',
                    lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                    maxHeight: 240, overflowY: 'auto', fontFamily: 'inherit',
                    color: 'var(--text-primary)', margin: 0,
                  }}>
                    {detailCall.transcription}
                  </pre>
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

      {/* Modal edição de pergunta */}
      {showModal && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal">
            <div className="modal-header">
              <h2>❓ {editQ.id ? 'Editar' : 'Nova'} Pergunta</h2>
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
                <label className="form-label">Valores válidos <span style={{ fontWeight: 400, color: 'var(--text-muted)' }}>(separados por vírgula — deixe em branco para resposta livre)</span></label>
                <input type="text" className="form-input" value={editQ.expectedValues ?? ''}
                  onChange={e => setEditQ(q => ({ ...q, expectedValues: e.target.value }))}
                  placeholder="Baixa,Média,Alta" />
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

// ─── Módulo URA principal ────────────────────────────────────────────────────

export default function ModuloURA() {
  const [tab, setTab] = useState<'calls' | 'fluxo' | 'dashboard'>('calls');
  const [calls, setCalls] = useState<CallRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [search, setSearch] = useState('');
  const [exporting, setExporting] = useState(false);

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

  useEffect(() => {
    if (tab === 'calls') loadCalls(0);
  }, [tab]);

  const handleSearchSubmit = (e: React.FormEvent) => { e.preventDefault(); loadCalls(0); };

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
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20 }}>
          <button className={`btn ${tab === 'calls'     ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('calls')}>
            📋 Chamadas
          </button>
          <button className={`btn ${tab === 'fluxo'     ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('fluxo')}>
            🔀 Fluxo URA
          </button>
          <button className={`btn ${tab === 'dashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('dashboard')}>
            📊 Dashboard
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
              <div className="toolbar-right">
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={exportUra}
                  disabled={exporting}
                  style={{ borderColor: 'rgba(124,58,237,0.4)', color: '#a78bfa', minWidth: 140 }}
                >
                  {exporting
                    ? <><span className="spinner" style={{ width: 12, height: 12, margin: '0 6px 0 0' }} />Exportando…</>
                    : '⬇ Exportar CSV'}
                </button>
              </div>
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
                      <tr key={c.id}
                        onClick={() => setDetailCall(c)}
                        style={{ cursor: 'pointer' }}
                        title="Clique para ver detalhes"
                      >
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

        {/* ---- FLUXO URA TAB ---- */}
        {tab === 'fluxo' && <FluxoURATab />}

        {/* ---- DASHBOARD TAB ---- */}
        {tab === 'dashboard' && <DashboardTab />}

      </div>
    </>
  );
}
