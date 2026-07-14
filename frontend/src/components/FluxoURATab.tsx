/**
 * FluxoURATab.tsx — Mensagens (boas-vindas/informativa/encerramento/VAD) e
 * perguntas de uma URA específica. Renderizado dentro de UraManagementTab
 * quando o admin está configurando uma URA.
 */
import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { UraQuestion } from '../api/types';

interface UraSetting {
  key: string;
  label: string;
  value: string;
  required: boolean;
}

export default function FluxoURATab({ uraId }: { uraId: number }) {
  const [settings, setSettings]       = useState<UraSetting[]>([]);
  const [questions, setQuestions]     = useState<UraQuestion[]>([]);
  const [loadingData, setLoadingData] = useState(true);
  const [loadError, setLoadError]     = useState<string | null>(null);
  const [saving, setSaving]           = useState<string | null>(null);
  const [saved, setSaved]             = useState<string | null>(null);
  const [localValues, setLocalValues] = useState<Record<string, string>>({});

  // Modal pergunta
  const [showModal, setShowModal] = useState(false);
  const [editQ, setEditQ]         = useState<Partial<UraQuestion>>({});

  const load = async () => {
    setLoadingData(true);
    setLoadError(null);
    try {
      const [s, q] = await Promise.all([
        api.get<UraSetting[]>(`/uras/${uraId}/settings`),
        api.get<UraQuestion[]>(`/uras/${uraId}/questions/all`),
      ]);
      setSettings(s.data);
      setLocalValues(Object.fromEntries(s.data.map(x => [x.key, x.value])));
      setQuestions(q.data);
    } catch (err) {
      setLoadError(getErrorMessage(err, 'Erro ao carregar o fluxo da URA. Tente novamente.'));
    } finally {
      setLoadingData(false);
    }
  };

  useEffect(() => { load(); }, [uraId]);

  const saveSetting = async (key: string) => {
    setSaving(key);
    try {
      await api.put(`/uras/${uraId}/settings/${key}`, { value: localValues[key] ?? '' });
      setSaved(key);
      setTimeout(() => setSaved(null), 2000);
      await load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar. Tente novamente.'));
    } finally {
      setSaving(null);
    }
  };

  const toggleActive = async (q: UraQuestion) => {
    try {
      await api.patch(`/uras/${uraId}/questions/${q.id}/active?active=${!q.isActive}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao atualizar a pergunta. Tente novamente.'));
    }
  };

  const openEditModal = (q?: UraQuestion) => {
    setEditQ(q ? { ...q } : { questionOrder: questions.length + 1, isActive: true });
    setShowModal(true);
  };

  const saveQuestion = async () => {
    if (!editQ.questionText?.trim())  { alert('Informe o texto da pergunta.'); return; }
    if (!editQ.jiraFieldKey?.trim())  { alert('Informe o campo do Jira.');     return; }
    if (!editQ.questionOrder)         { alert('Informe a ordem.');             return; }
    try {
      if (editQ.id) { await api.put(`/uras/${uraId}/questions/${editQ.id}`, editQ); }
      else          { await api.post(`/uras/${uraId}/questions`, editQ); }
      setShowModal(false);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar a pergunta. Tente novamente.'));
    }
  };

  const deleteQuestion = async (id: number) => {
    if (!confirm('Remover esta pergunta?')) return;
    try {
      await api.delete(`/uras/${uraId}/questions/${id}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover a pergunta. Tente novamente.'));
    }
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

  if (loadError) {
    return (
      <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
        <p style={{ marginBottom: 12 }}>{loadError}</p>
        <button className="btn btn-primary btn-sm" onClick={load}>Tentar novamente</button>
      </div>
    );
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
            ) : [...questions]
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
