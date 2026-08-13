import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, X } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type { BusinessUnit, CostAlertConfigView, Survey, SurveyMode, SurveyRequest } from '../api/types';
import { useAuthSession } from '../hooks/useAuthSession';

const MODE_LABEL: Record<SurveyMode, string> = {
  DTMF_SIMPLES: 'DTMF simples (1 pergunta, dígito)',
  DTMF_MULTI: 'DTMF múltiplas perguntas (dígito cada)',
  FALADA_IA: 'Resposta falada (transcrita/classificada por IA — gera custo)',
  DTMF_COMENTARIO: 'Nota por dígito + comentário gravado opcional',
};

const MODE_COST_NOTE: Record<SurveyMode, string> = {
  DTMF_SIMPLES: 'Sem custo de IA.',
  DTMF_MULTI: 'Sem custo de IA.',
  FALADA_IA: 'Gera custo de IA a cada resposta (transcrição + classificação, processada após a chamada).',
  DTMF_COMENTARIO: 'A nota não gera custo. O comentário gravado só é transcrito sob demanda, nunca automaticamente.',
};

const EMPTY_FORM: SurveyRequest = {
  name: '', mode: 'DTMF_SIMPLES', scaleMax: 10, businessUnitId: null,
  questions: [{ orderIndex: 1, text: '', audioPath: null }],
};

function formatUsd(value: number) {
  return `US$ ${value.toFixed(2)}`;
}

/** Alerta de gasto de IA da frente callcenter_nps (Fase 21, §21.5) — só existe consumo real
 * quando alguma pesquisa usa o modo FALADA_IA; DTMF nunca gera custo. Mesmo padrão de
 * CostAlertPanel.tsx do Financeiro (Telecom), duplicado aqui porque esta SPA não tem acesso
 * ao módulo Financeiro do shell. */
function NpsCostAlertPanel() {
  const { hasWrite } = useAuthSession();
  const canEdit = hasWrite('financeiro.callcenter_nps');
  const [config, setConfig] = useState<CostAlertConfigView | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [threshold, setThreshold] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api.get<CostAlertConfigView>('/financeiro/cost-alerts/callcenter_nps')
      .then(({ data }) => { setConfig(data); setEnabled(data.enabled); setThreshold(String(data.thresholdUsd)); })
      .catch(() => setConfig(null));
  }, []);

  const save = () => {
    const thresholdUsd = Number(threshold);
    if (!Number.isFinite(thresholdUsd) || thresholdUsd < 0) return;
    setSaving(true);
    api.put<CostAlertConfigView>('/financeiro/cost-alerts/callcenter_nps', { thresholdUsd, enabled })
      .then(({ data }) => setConfig(data))
      .finally(() => setSaving(false));
  };

  return (
    <div className="card" style={{ marginTop: 24, maxWidth: 480 }}>
      <h3 style={{ marginBottom: 4, fontSize: '0.95rem' }}>🔔 Alerta de gasto de IA (Financeiro)</h3>
      <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 16 }}>
        Custo de transcrição/classificação de pesquisas em modo "Resposta falada" — o único modo
        que gera custo de IA. Ultrapassar o limite mensal dispara um alerta único no Telegram.
      </p>
      <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.85rem', marginBottom: 14 }}>
        <input type="checkbox" checked={enabled} disabled={!canEdit} onChange={e => setEnabled(e.target.checked)} />
        Habilitar alerta
      </label>
      <div className="form-group" style={{ marginBottom: 16 }}>
        <label className="form-label">Limite mensal (USD)</label>
        <input type="number" min="0" step="0.01" className="form-input" style={{ maxWidth: 200 }}
          value={threshold} disabled={!canEdit} onChange={e => setThreshold(e.target.value)} />
      </div>
      {config && (
        <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: 16 }}>
          Gasto no mês corrente: <strong>{formatUsd(config.currentMonthSpendUsd)}</strong>
          {config.lastNotifiedMonth && <> — último alerta em {config.lastNotifiedMonth}</>}
        </p>
      )}
      {canEdit && (
        <button className="btn btn-primary btn-sm" onClick={save} disabled={saving}>
          {saving ? 'Salvando…' : 'Salvar'}
        </button>
      )}
    </div>
  );
}

export function PesquisasTab({ canWrite }: { canWrite: boolean }) {
  const [surveys, setSurveys] = useState<Survey[]>([]);
  const [businessUnits, setBusinessUnits] = useState<BusinessUnit[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<Survey | null>(null);
  const [fd, setFd] = useState<SurveyRequest>(EMPTY_FORM);
  const [msg, setMsg] = useState('');

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 5000); };

  const load = () => {
    api.get<Survey[]>('/callcenter/surveys').then(({ data }) => setSurveys(data)).catch(() => setSurveys([]));
  };
  useEffect(() => {
    load();
    api.get<BusinessUnit[]>('/business-units?active=true').then(({ data }) => setBusinessUnits(data)).catch(() => setBusinessUnits([]));
  }, []);

  const openForm = (s: Survey | null) => {
    setEditing(s);
    setFd(s ? {
      name: s.name, mode: s.mode, scaleMax: s.scaleMax, businessUnitId: s.businessUnitId ?? null,
      questions: s.questions.map(q => ({ orderIndex: q.orderIndex, text: q.text, audioPath: q.audioPath ?? null })),
    } : EMPTY_FORM);
    setShowForm(true);
  };

  const save = () => {
    const req = editing ? api.put(`/callcenter/surveys/${editing.id}`, fd) : api.post('/callcenter/surveys', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar pesquisa.')));
  };

  const toggleActive = (s: Survey) => {
    api.put(`/callcenter/surveys/${s.id}/active`, { active: !s.active })
      .then(load)
      .catch(err => flash(getErrorMessage(err, 'Erro ao atualizar pesquisa.')));
  };

  const addQuestion = () => {
    setFd(f => ({ ...f, questions: [...f.questions, { orderIndex: f.questions.length + 1, text: '', audioPath: null }] }));
  };
  const removeQuestion = (idx: number) => {
    setFd(f => ({
      ...f,
      questions: f.questions.filter((_, i) => i !== idx).map((q, i) => ({ ...q, orderIndex: i + 1 })),
    }));
  };
  const updateQuestionText = (idx: number, text: string) => {
    setFd(f => ({ ...f, questions: f.questions.map((q, i) => (i === idx ? { ...q, text } : q)) }));
  };

  return (
    <>
      <div className="page-header">
        <h1>Pesquisas de Satisfação (NPS)</h1>
        <p>4 modos de coleta pós-atendimento — DTMF (sem custo) ou resposta falada (com custo de IA)</p>
      </div>
      <div className="page-body">
        {msg && <div className="alert alert-error" style={{ marginBottom: 16 }}>{msg}</div>}

        {canWrite && (
          <button className="btn btn-primary" style={{ marginBottom: 16 }} onClick={() => openForm(null)}>
            <Plus size={16} /> Nova pesquisa
          </button>
        )}

        <div className="card">
          <table className="table">
            <thead>
              <tr><th>Nome</th><th>Modo</th><th>Escala</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              {surveys.map(s => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td>{MODE_LABEL[s.mode]}</td>
                  <td>0-{s.scaleMax === 10 ? '9 e *' : s.scaleMax}</td>
                  <td>{s.active ? 'Ativa' : 'Inativa'}</td>
                  <td className="flex items-center" style={{ gap: 8 }}>
                    {canWrite && (
                      <>
                        <button className="btn btn-ghost btn-sm" onClick={() => openForm(s)}><Pencil size={14} /></button>
                        <button className="btn btn-ghost btn-sm" onClick={() => toggleActive(s)}>
                          {s.active ? 'Desativar' : 'Ativar'}
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {surveys.length === 0 && <tr><td colSpan={5}>Nenhuma pesquisa cadastrada.</td></tr>}
            </tbody>
          </table>
        </div>

        <NpsCostAlertPanel />
      </div>

      {showForm && (
        <div className="modal-overlay" onClick={() => setShowForm(false)}>
          <div className="modal" style={{ maxWidth: 640 }} onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h3>{editing ? 'Editar pesquisa' : 'Nova pesquisa'}</h3>
              <button className="btn btn-ghost btn-sm" onClick={() => setShowForm(false)}><X size={16} /></button>
            </div>
            <div className="modal-body">
              {editing && (
                <div className="alert alert-warning" style={{ marginBottom: 12 }}>
                  Editar uma pesquisa que já tem resposta registrada é bloqueado pelo backend — crie uma
                  nova pesquisa nesse caso, em vez de alterar esta.
                </div>
              )}
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Nome</label>
                  <input className="form-input" value={fd.name} onChange={e => setFd(f => ({ ...f, name: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">BU (opcional)</label>
                  <select className="form-input" value={fd.businessUnitId ?? ''}
                    onChange={e => setFd(f => ({ ...f, businessUnitId: e.target.value ? Number(e.target.value) : null }))}>
                    <option value="">— Todas —</option>
                    {businessUnits.map(bu => <option key={bu.id} value={bu.id}>{bu.name}</option>)}
                  </select>
                </div>
                <div className="form-group">
                  <label className="form-label">Modo de coleta</label>
                  <select className="form-input" value={fd.mode} disabled={!!editing}
                    onChange={e => setFd(f => ({ ...f, mode: e.target.value as SurveyMode }))}>
                    {(Object.keys(MODE_LABEL) as SurveyMode[]).map(m => (
                      <option key={m} value={m}>{MODE_LABEL[m]}</option>
                    ))}
                  </select>
                  <p style={{ fontSize: '.8rem', color: 'var(--text-muted)', marginTop: 4 }}>{MODE_COST_NOTE[fd.mode]}</p>
                </div>
                <div className="form-group">
                  <label className="form-label">Escala da nota</label>
                  <select className="form-input" value={fd.scaleMax} disabled={!!editing}
                    onChange={e => setFd(f => ({ ...f, scaleMax: Number(e.target.value) }))}>
                    <option value={10}>0-9 mais * (=10)</option>
                    <option value={5}>0-5</option>
                  </select>
                  <p style={{ fontSize: '.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
                    Escalas diferentes produzem NPS incomparável — fixa depois de criada.
                  </p>
                </div>
              </div>

              <div style={{ marginTop: 16 }}>
                <div className="flex items-center justify-between">
                  <strong>Perguntas</strong>
                  <button className="btn btn-ghost btn-sm" onClick={addQuestion}><Plus size={14} /> Adicionar</button>
                </div>
                {fd.questions.map((q, idx) => (
                  <div key={idx} className="flex items-center" style={{ gap: 8, marginTop: 8 }}>
                    <span style={{ color: 'var(--text-muted)' }}>{idx + 1}.</span>
                    <input className="form-input" style={{ flex: 1 }} placeholder="Texto da pergunta"
                      value={q.text} onChange={e => updateQuestionText(idx, e.target.value)} />
                    {fd.questions.length > 1 && (
                      <button className="btn btn-ghost btn-sm" onClick={() => removeQuestion(idx)}><Trash2 size={14} /></button>
                    )}
                  </div>
                ))}
                {fd.mode === 'DTMF_SIMPLES' && fd.questions.length > 1 && (
                  <p style={{ fontSize: '.8rem', color: 'var(--text-muted)', marginTop: 8 }}>
                    DTMF simples usa só a primeira pergunta — as demais não serão executadas.
                  </p>
                )}
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save}
                disabled={!fd.name.trim() || fd.questions.some(q => !q.text.trim())}>
                Salvar
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
