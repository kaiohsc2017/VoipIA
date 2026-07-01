import { useEffect, useState, useCallback } from 'react';
import { subscribe } from '../api/websocket';
import api from '../api/client';
import type { CallRecord, UraQuestion, PageResponse, Ura } from '../api/types';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import UraManagementTab from './UraManagementTab';

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

/**
 * Busca o áudio via api.get (anexa o JWT) e reproduz como blob — uma tag
 * <audio src="/api/..."> direta não funciona porque o endpoint exige
 * autenticação e o navegador não anexa o header Authorization nesse caso.
 */
function AuthedAudio({ callId, style, autoPlay, onError }: {
  callId: number;
  style?: React.CSSProperties;
  autoPlay?: boolean;
  onError?: () => void;
}) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let objectUrl: string | null = null;
    api.get(`/calls/${callId}/audio`, { responseType: 'blob' })
      .then(res => {
        objectUrl = URL.createObjectURL(res.data);
        setSrc(objectUrl);
      })
      .catch(() => { setFailed(true); onError?.(); });
    return () => { if (objectUrl) URL.revokeObjectURL(objectUrl); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [callId]);

  if (failed) return <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>Erro ao carregar áudio</span>;
  if (!src) return <span className="spinner" style={{ width: 16, height: 16 }} />;
  return <audio controls autoPlay={autoPlay} src={src} style={style} />;
}

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
    <AuthedAudio
      callId={callId}
      autoPlay
      style={{ height: 28, minWidth: 180, maxWidth: 240 }}
      onError={() => setShow(false)}
    />
  );
}

// ─── Aba Fluxo URA ───────────────────────────────────────────────────────────

function FluxoURATab({ uraId }: { uraId: number }) {
  const [settings, setSettings]       = useState<UraSetting[]>([]);
  const [questions, setQuestions]     = useState<UraQuestion[]>([]);
  const [loadingData, setLoadingData] = useState(true);
  const [saving, setSaving]           = useState<string | null>(null);
  const [saved, setSaved]             = useState<string | null>(null);
  const [localValues, setLocalValues] = useState<Record<string, string>>({});

  // Modal pergunta
  const [showModal, setShowModal] = useState(false);
  const [editQ, setEditQ]         = useState<Partial<UraQuestion>>({});

  const load = async () => {
    setLoadingData(true);
    const [s, q] = await Promise.all([
      api.get<UraSetting[]>(`/uras/${uraId}/settings`),
      api.get<UraQuestion[]>(`/uras/${uraId}/questions/all`),
    ]);
    setSettings(s.data);
    setLocalValues(Object.fromEntries(s.data.map(x => [x.key, x.value])));
    setQuestions(q.data);
    setLoadingData(false);
  };

  useEffect(() => { load(); }, [uraId]);

  const saveSetting = async (key: string) => {
    setSaving(key);
    try {
      await api.put(`/uras/${uraId}/settings/${key}`, { value: localValues[key] ?? '' });
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
    await api.patch(`/uras/${uraId}/questions/${q.id}/active?active=${!q.isActive}`);
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
    if (editQ.id) { await api.put(`/uras/${uraId}/questions/${editQ.id}`, editQ); }
    else          { await api.post(`/uras/${uraId}/questions`, editQ); }
    setShowModal(false);
    load();
  };

  const deleteQuestion = async (id: number) => {
    if (confirm('Remover esta pergunta?')) { await api.delete(`/uras/${uraId}/questions/${id}`); load(); }
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

      {/* ── Configurações avançadas ── */}
      {settingsByKey['vad_aggressiveness'] && (
        <div className="table-wrapper" style={{ padding: 0, overflow: 'visible', marginTop: 20 }}>
          <div style={{ padding: '18px 20px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <div style={{
                  width: 30, height: 30, borderRadius: '50%', background: 'var(--color-background-secondary)',
                  border: '0.5px solid var(--color-border-tertiary)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, flexShrink: 0,
                }}>🎙️</div>
                <div>
                  <div style={{ fontWeight: 500, fontSize: 13 }}>Sensibilidade a ruído de fundo (VAD)</div>
                  <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 2 }}>
                    Controla quando a URA entende que o cliente terminou de falar (2s de silêncio). Níveis mais
                    altos ignoram melhor som ambiente/ao redor, mas podem cortar respostas bem baixinhas.
                  </div>
                </div>
              </div>
              <button
                className="btn btn-primary btn-sm"
                onClick={() => saveSetting('vad_aggressiveness')}
                disabled={saving === 'vad_aggressiveness' || localValues['vad_aggressiveness'] === settingsByKey['vad_aggressiveness'].value}
                style={{ minWidth: 80, fontSize: 12 }}
              >
                {saving === 'vad_aggressiveness'
                  ? <><span className="spinner" style={{ width: 10, height: 10, margin: '0 4px 0 0' }} />Salvando…</>
                  : saved === 'vad_aggressiveness' ? '✓ Salvo' : 'Salvar'}
              </button>
            </div>
            <select
              className="form-select"
              value={localValues['vad_aggressiveness'] ?? '3'}
              onChange={e => setLocalValues(v => ({ ...v, vad_aggressiveness: e.target.value }))}
              style={{ maxWidth: 420 }}
            >
              <option value="0">0 — Menos sensível (aceita mais ruído como se fosse silêncio)</option>
              <option value="1">1 — Baixa (ainda tolera bastante ruído de fundo)</option>
              <option value="2">2 — Média (equilíbrio entre ignorar ruído e não cortar respostas baixinhas)</option>
              <option value="3">3 — Alta (recomendado — foca na voz, ignora som ao redor)</option>
            </select>
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
  const [tab, setTab] = useState<'calls' | 'fluxo' | 'dashboard' | 'uras'>('calls');
  const [uras, setUras] = useState<Ura[]>([]);
  const [selectedUraId, setSelectedUraId] = useState<number>(1);
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

  const hasActiveFilters = !!(dateFrom || dateTo || clientNameFilter || ramalFilter
    || callTypeFilter || jiraKeyFilter || transcriptionFilter || priorityFilter);

  const loadCalls = (p = 0) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '20', uraId: String(selectedUraId) });
    if (search) params.set('callerNumber', search);
    if (dateFrom) params.set('dateFrom', dateFrom);
    if (dateTo) params.set('dateTo', dateTo);
    if (clientNameFilter) params.set('clientName', clientNameFilter);
    if (ramalFilter) params.set('ramal', ramalFilter);
    if (callTypeFilter) params.set('callType', callTypeFilter);
    if (jiraKeyFilter) params.set('jiraIssueKey', jiraKeyFilter);
    if (transcriptionFilter) params.set('transcriptionText', transcriptionFilter);
    if (priorityFilter) params.set('priority', priorityFilter);
    api.get<PageResponse<CallRecord>>(`/calls?${params}`)
      .then(r => {
        setCalls(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    api.get<Ura[]>('/uras').then(r => setUras(r.data));
  }, []);

  useEffect(() => {
    if (tab === 'calls') loadCalls(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, selectedUraId]);

  const handleSearchSubmit = (e: React.FormEvent) => { e.preventDefault(); loadCalls(0); };

  const clearFilters = () => {
    setDateFrom(''); setDateTo(''); setClientNameFilter(''); setRamalFilter('');
    setCallTypeFilter(''); setJiraKeyFilter(''); setTranscriptionFilter(''); setPriorityFilter('');
    // Recarrega já sem os filtros — precisa esperar o próximo tick para os estados aplicarem
    setTimeout(() => loadCalls(0), 0);
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
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20, justifyContent: 'space-between', display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          <div className="flex gap-1" style={{ display: 'flex', gap: 6 }}>
            <button className={`btn ${tab === 'calls'     ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('calls')}>
              📋 Chamadas
            </button>
            <button className={`btn ${tab === 'fluxo'     ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('fluxo')}>
              🔀 Fluxo URA
            </button>
            <button className={`btn ${tab === 'dashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('dashboard')}>
              📊 Dashboard
            </button>
            <button className={`btn ${tab === 'uras'      ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('uras')}>
              🎛️ URAs
            </button>
          </div>
          {(tab === 'calls' || tab === 'fluxo') && (
            <select
              className="form-select"
              style={{ maxWidth: 260 }}
              value={selectedUraId}
              onChange={e => setSelectedUraId(Number(e.target.value))}
            >
              {uras.map(u => (
                <option key={u.id} value={u.id}>{u.name} (ramal {u.extension})</option>
              ))}
            </select>
          )}
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
                  <AuthedAudio callId={detailCall.id} style={{ width: '100%', height: 36 }} />
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
                  <input className="search-input" placeholder="Filtrar por número..."
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
                  style={{ borderColor: 'rgba(124,58,237,0.4)', color: '#a78bfa', minWidth: 140 }}
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
                            ? <span className="badge" style={{ background: c.callType.toLowerCase().includes('incidente') ? 'rgba(239,68,68,0.1)' : 'rgba(59,130,246,0.1)', color: c.callType.toLowerCase().includes('incidente') ? '#dc2626' : '#2563eb' }}>{c.callType}</span>
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

        {/* ---- FLUXO URA TAB ---- */}
        {tab === 'fluxo' && <FluxoURATab uraId={selectedUraId} />}

        {/* ---- DASHBOARD TAB ---- */}
        {tab === 'dashboard' && <DashboardTab />}

        {/* ---- URAs TAB ---- */}
        {tab === 'uras' && <UraManagementTab onSelect={id => { setSelectedUraId(id); setTab('fluxo'); }} />}

      </div>
    </>
  );
}
