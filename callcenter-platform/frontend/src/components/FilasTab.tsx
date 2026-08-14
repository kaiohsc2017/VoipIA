import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, Users, X, Star, RefreshCw } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { BusinessUnit, CcAgent, CcQueue, CcQueueMember, CcQueueSkill, CcSkill, QueueRequest, SkillRecalculationResult, SurveySummary } from '../api/types';

const STRATEGIES = ['ringall', 'leastrecent', 'fewestcalls', 'random', 'rrmemory', 'linear'];

const EMPTY_FORM: QueueRequest = {
  name: '', displayName: '', businessUnitId: null, strategy: 'ringall', timeoutSeconds: 15,
  recordingEnabled: true, consentMessagePath: null, copyMembersFromQueueId: null,
  surveyId: null, npsAlertEnabled: false, npsAlertThreshold: null, maxConcurrentChats: null,
};

export function FilasTab({ canWrite }: { canWrite: boolean }) {
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [businessUnits, setBusinessUnits] = useState<BusinessUnit[]>([]);
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [surveys, setSurveys] = useState<SurveySummary[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcQueue | null>(null);
  const [confirmQueue, setConfirmQueue] = useState<CcQueue | null>(null);
  const [membersOf, setMembersOf] = useState<CcQueue | null>(null);
  const [members, setMembers] = useState<CcQueueMember[]>([]);
  const [skillsOf, setSkillsOf] = useState<CcQueue | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<QueueRequest>(EMPTY_FORM);
  const [addPriority, setAddPriority] = useState('0');

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    api.get<CcQueue[]>('/callcenter/filas')
      .then(({ data }) => setQueues(data))
      .catch(() => setQueues([]));
  };
  useEffect(() => {
    load();
    api.get<BusinessUnit[]>('/business-units?active=true').then(({ data }) => setBusinessUnits(data)).catch(() => setBusinessUnits([]));
    api.get<CcAgent[]>('/callcenter/agentes').then(({ data }) => setAgents(data)).catch(() => setAgents([]));
    api.get<SurveySummary[]>('/callcenter/surveys').then(({ data }) => setSurveys(data)).catch(() => setSurveys([]));
  }, []);

  const openForm = (q: CcQueue | null) => {
    setEditing(q);
    setFd(q ? {
      name: q.name, displayName: q.displayName,
      businessUnitId: q.businessUnit?.id ?? null, strategy: q.strategy, timeoutSeconds: q.timeoutSeconds,
      recordingEnabled: q.recordingEnabled, consentMessagePath: q.consentMessagePath ?? null,
      surveyId: q.survey?.id ?? null, npsAlertEnabled: q.npsAlertEnabled, npsAlertThreshold: q.npsAlertThreshold ?? null,
      maxConcurrentChats: q.maxConcurrentChats ?? null,
    } : EMPTY_FORM);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/filas/${editing.id}`, fd)
      : api.post('/callcenter/filas', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar fila.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/filas/${id}`)
      .then(() => setQueues(list => list.filter(q => q.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover fila.')));
  };

  const openMembers = (q: CcQueue) => {
    setMembersOf(q);
    api.get<CcQueueMember[]>(`/callcenter/filas/${q.id}/membros`).then(({ data }) => setMembers(data)).catch(() => setMembers([]));
  };

  const addMember = (agentId: number, penalty: number) => {
    if (!membersOf) return;
    api.post<CcQueueMember>(`/callcenter/filas/${membersOf.id}/membros/${agentId}`, { penalty })
      .then(({ data }) => setMembers(list => [...list, data]))
      .catch(err => flash(getErrorMessage(err, 'Erro ao adicionar agente na fila.')));
  };

  const updateMemberPriority = (agentId: number, penalty: number) => {
    if (!membersOf) return;
    api.put(`/callcenter/filas/${membersOf.id}/membros/${agentId}/prioridade`, { penalty })
      .then(() => setMembers(list => list.map(m => m.agent.id === agentId ? { ...m, penalty } : m)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao atualizar prioridade.')));
  };

  const removeMember = (agentId: number) => {
    if (!membersOf) return;
    api.delete(`/callcenter/filas/${membersOf.id}/membros/${agentId}`)
      .then(() => setMembers(list => list.filter(m => m.agent.id !== agentId)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover agente da fila.')));
  };

  const availableAgents = agents.filter(a => !members.some(m => m.agent.id === a.id));

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Filas</h1><p>Filas de atendimento (Asterisk Realtime)</p></div>
          {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Nova fila</button>}
        </div>
      </div>
      <div className="page-body">
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmQueue && (
          <ConfirmModal
            message={`Remover a fila "${confirmQueue.displayName}"?`}
            onConfirm={() => { del(confirmQueue.id); setConfirmQueue(null); }}
            onCancel={() => setConfirmQueue(null)}
          />
        )}

        {canWrite && showForm && (
          <div className="modal-overlay" onClick={() => setShowForm(false)}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>{editing ? 'Editar Fila' : 'Nova Fila'}</h2>
                <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
              </div>
              <div className="modal-body">
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">Nome técnico (Asterisk)</label>
                    <input className="form-input" value={fd.name} disabled={!!editing}
                      onChange={e => setFd(f => ({ ...f, name: e.target.value }))} />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Nome de exibição</label>
                    <input className="form-input" value={fd.displayName}
                      onChange={e => setFd(f => ({ ...f, displayName: e.target.value }))} />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Unidade de negócio</label>
                    <select className="form-input" value={fd.businessUnitId ?? ''}
                      onChange={e => setFd(f => ({ ...f, businessUnitId: e.target.value ? Number(e.target.value) : null }))}>
                      <option value="">— Nenhuma —</option>
                      {businessUnits.map(bu => <option key={bu.id} value={bu.id}>{bu.name}</option>)}
                    </select>
                  </div>
                  <div className="form-group">
                    <label className="form-label">Estratégia</label>
                    <select className="form-input" value={fd.strategy}
                      onChange={e => setFd(f => ({ ...f, strategy: e.target.value }))}>
                      {STRATEGIES.map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                  </div>
                  <div className="form-group">
                    <label className="form-label">Timeout (segundos)</label>
                    <input type="number" className="form-input" value={fd.timeoutSeconds}
                      onChange={e => setFd(f => ({ ...f, timeoutSeconds: Number(e.target.value) }))} />
                  </div>
                  <div className="form-group">
                    <label className="form-label" htmlFor="fila-max-concurrent-chats">
                      Limite de chats simultâneos por agente (Fase 7c)
                    </label>
                    <input id="fila-max-concurrent-chats" type="number" min={1} className="form-input"
                      placeholder="Sem limite"
                      value={fd.maxConcurrentChats ?? ''}
                      onChange={e => setFd(f => ({ ...f, maxConcurrentChats: e.target.value ? Number(e.target.value) : null }))} />
                    <span style={{ fontSize: '0.78rem', opacity: 0.7 }}>
                      Só vale para agentes desta fila sem limite próprio configurado — o valor do agente sempre prevalece.
                    </span>
                  </div>
                  <div className="form-group">
                    <label className="form-label">
                      <input type="checkbox" checked={fd.recordingEnabled ?? true}
                        onChange={e => setFd(f => ({ ...f, recordingEnabled: e.target.checked }))} />
                      {' '}Gravar chamadas desta fila
                    </label>
                  </div>
                  <div className="form-group">
                    <label className="form-label">Áudio de aviso de gravação (caminho no volume)</label>
                    <input className="form-input" placeholder="/opt/AsteriskIA/media/gravacao/avisos/consentimento.wav"
                      value={fd.consentMessagePath ?? ''}
                      onChange={e => setFd(f => ({ ...f, consentMessagePath: e.target.value || null }))} />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Pesquisa de satisfação (NPS) — Fase 21</label>
                    <select className="form-input" value={fd.surveyId ?? ''}
                      onChange={e => setFd(f => ({ ...f, surveyId: e.target.value ? Number(e.target.value) : null }))}>
                      <option value="">— Sem pesquisa —</option>
                      {surveys.filter(s => s.active).map(s => (
                        <option key={s.id} value={s.id}>{s.name} ({s.mode})</option>
                      ))}
                    </select>
                  </div>
                  {fd.surveyId != null && (
                    <div className="form-group">
                      <label className="form-label">
                        <input type="checkbox" checked={fd.npsAlertEnabled ?? false}
                          onChange={e => setFd(f => ({ ...f, npsAlertEnabled: e.target.checked }))} />
                        {' '}Alertar no Telegram quando a nota for baixa
                      </label>
                      {fd.npsAlertEnabled && (
                        <input type="number" className="form-input" style={{ marginTop: 6 }}
                          placeholder="Nota igual ou abaixo da qual dispara o alerta"
                          value={fd.npsAlertThreshold ?? ''}
                          onChange={e => setFd(f => ({ ...f, npsAlertThreshold: e.target.value ? Number(e.target.value) : null }))} />
                      )}
                    </div>
                  )}
                  {!editing && (
                    <div className="form-group">
                      <label className="form-label">
                        <input type="checkbox" checked={!!fd.copyMembersFromQueueId}
                          onChange={e => setFd(f => ({ ...f, copyMembersFromQueueId: e.target.checked ? (queues[0]?.id ?? null) : null }))} />
                        {' '}Copiar membros de outra fila
                      </label>
                      {fd.copyMembersFromQueueId != null && (
                        <select className="form-input" style={{ marginTop: 6 }} value={fd.copyMembersFromQueueId}
                          onChange={e => setFd(f => ({ ...f, copyMembersFromQueueId: Number(e.target.value) }))}>
                          {queues.map(q => <option key={q.id} value={q.id}>{q.displayName} ({q.name})</option>)}
                        </select>
                      )}
                    </div>
                  )}
                </div>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
                <button className="btn btn-primary" onClick={save} disabled={!fd.name.trim() || !fd.displayName.trim()}>Salvar</button>
              </div>
            </div>
          </div>
        )}

        {skillsOf && (
          <QueueSkillsModal queue={skillsOf} canWrite={canWrite} onClose={() => setSkillsOf(null)}
            onError={m => flash(m)} onInfo={m => flash(m)} />
        )}

        {membersOf && (
          <div className="modal-overlay" onClick={() => setMembersOf(null)}>
            <div className="modal modal-lg" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>Agentes da fila "{membersOf.displayName}"</h2>
                <button className="btn-close" onClick={() => setMembersOf(null)}>×</button>
              </div>
              <div className="modal-body">
                <div className="table-wrapper">
                  <table>
                    <thead><tr><th>Agente</th><th>Ramal</th><th>Prioridade</th>{canWrite && <th></th>}</tr></thead>
                    <tbody>
                      {members.map(m => (
                        <tr key={m.agent.id}>
                          <td>{m.agent.name}</td>
                          <td>{m.agent.extension?.extension}</td>
                          <td>
                            {canWrite ? (
                              <input type="number" min={0} className="form-input" style={{ width: 70 }}
                                defaultValue={m.penalty}
                                onBlur={e => {
                                  const v = Number(e.target.value);
                                  if (!Number.isNaN(v) && v !== m.penalty) updateMemberPriority(m.agent.id, v);
                                }} />
                            ) : m.penalty}
                          </td>
                          {canWrite && <td><button className="btn btn-ghost btn-sm" onClick={() => removeMember(m.agent.id)}><X size={14} /></button></td>}
                        </tr>
                      ))}
                      {members.length === 0 && <tr><td colSpan={canWrite ? 4 : 3} className="table-empty">Nenhum agente nesta fila.</td></tr>}
                    </tbody>
                  </table>
                </div>
                {canWrite && availableAgents.length > 0 && (
                  <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
                    <select className="form-input" value="" onChange={e => { if (e.target.value) { addMember(Number(e.target.value), Number(addPriority) || 0); setAddPriority('0'); } }}>
                      <option value="">— Selecione um agente —</option>
                      {availableAgents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
                    </select>
                    <input type="number" min={0} className="form-input" style={{ width: 90 }}
                      placeholder="Prioridade" value={addPriority} onChange={e => setAddPriority(e.target.value)} />
                  </div>
                )}
                <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 8 }}>
                  Prioridade: menor valor é atendido antes (mesma semântica do Asterisk).
                </p>
              </div>
            </div>
          </div>
        )}

        <div className="table-wrapper">
          <table>
            <thead>
              <tr><th>Nome</th><th>BU</th><th>Estratégia</th><th>Timeout</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              {queues.map(q => (
                <tr key={q.id}>
                  <td>{q.displayName} <span style={{ color: 'var(--text-muted)' }}>({q.name})</span></td>
                  <td>{q.businessUnit?.name ?? '—'}</td>
                  <td>{q.strategy}</td>
                  <td>{q.timeoutSeconds}s</td>
                  <td><span className={`badge ${q.active ? 'badge-success' : 'badge-gray'}`}>{q.active ? 'Ativa' : 'Inativa'}</span></td>
                  <td>
                    <button className="btn btn-ghost btn-sm" onClick={() => openMembers(q)}><Users size={14} /></button>
                    <button className="btn btn-ghost btn-sm" title="Skills exigidas" onClick={() => setSkillsOf(q)}><Star size={14} /></button>
                    {canWrite && (
                      <>
                        <button className="btn btn-ghost btn-sm" onClick={() => openForm(q)}><Pencil size={14} /></button>
                        <button className="btn btn-ghost btn-sm" onClick={() => setConfirmQueue(q)}><Trash2 size={14} /></button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {queues.length === 0 && <tr><td colSpan={6} className="table-empty">Nenhuma fila cadastrada.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

/**
 * QueueSkillsModal — skills exigidas por uma fila com nível mínimo (Fase 5f.1) + botão de
 * recálculo explícito de participação. O recálculo NUNCA roda sozinho (sem job/scheduler) — só
 * quando o operador clica no botão aqui, e nunca toca na prioridade (penalty) de quem já é
 * membro, só adiciona/remove elegibilidade (ver CallCenterSkillRoutingService no backend).
 */
function QueueSkillsModal({ queue, canWrite, onClose, onError, onInfo }: {
  queue: CcQueue; canWrite: boolean; onClose: () => void; onError: (m: string) => void; onInfo: (m: string) => void;
}) {
  const [queueSkills, setQueueSkills] = useState<CcQueueSkill[]>([]);
  const [allSkills, setAllSkills] = useState<CcSkill[]>([]);
  const [addSkillId, setAddSkillId] = useState('');
  const [addMinLevel, setAddMinLevel] = useState('1');
  const [recalculating, setRecalculating] = useState(false);

  const load = () => {
    api.get<CcQueueSkill[]>(`/callcenter/filas/${queue.id}/skills`)
      .then(({ data }) => setQueueSkills(data))
      .catch(err => onError(getErrorMessage(err, 'Erro ao listar skills exigidas pela fila.')));
  };
  useEffect(() => {
    load();
    api.get<CcSkill[]>('/callcenter/skills').then(({ data }) => setAllSkills(data)).catch(() => setAllSkills([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queue.id]);

  const availableSkills = allSkills.filter(s => !queueSkills.some(qs => qs.skill.id === s.id));

  const add = () => {
    if (!addSkillId) return;
    api.put(`/callcenter/filas/${queue.id}/skills/${addSkillId}`, { minLevel: Number(addMinLevel) || 1 })
      .then(() => { load(); setAddSkillId(''); setAddMinLevel('1'); })
      .catch(err => onError(getErrorMessage(err, 'Erro ao exigir skill.')));
  };

  const updateMinLevel = (skillId: number, minLevel: number) => {
    api.put(`/callcenter/filas/${queue.id}/skills/${skillId}`, { minLevel })
      .then(load)
      .catch(err => onError(getErrorMessage(err, 'Erro ao atualizar nível mínimo.')));
  };

  const remove = (skillId: number) => {
    api.delete(`/callcenter/filas/${queue.id}/skills/${skillId}`)
      .then(load)
      .catch(err => onError(getErrorMessage(err, 'Erro ao remover exigência de skill.')));
  };

  const recalculate = () => {
    setRecalculating(true);
    api.post<SkillRecalculationResult>(`/callcenter/filas/${queue.id}/recalcular-skills`)
      .then(({ data }) => onInfo(`Recálculo concluído: ${data.added} agente(s) adicionado(s), ${data.removed} removido(s).`))
      .catch(err => onError(getErrorMessage(err, 'Erro ao recalcular participação por skill.')))
      .finally(() => setRecalculating(false));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Skills exigidas por "{queue.displayName}"</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          <div className="table-wrapper">
            <table>
              <thead><tr><th>Skill</th><th>Nível mínimo (1-5)</th>{canWrite && <th></th>}</tr></thead>
              <tbody>
                {queueSkills.map(qs => (
                  <tr key={qs.skill.id}>
                    <td>{qs.skill.name}</td>
                    <td>
                      {canWrite ? (
                        <input type="number" min={1} max={5} className="form-input" style={{ width: 60 }}
                          defaultValue={qs.minLevel}
                          onBlur={e => {
                            const v = Number(e.target.value);
                            if (v >= 1 && v <= 5 && v !== qs.minLevel) updateMinLevel(qs.skill.id, v);
                          }} />
                      ) : qs.minLevel}
                    </td>
                    {canWrite && (
                      <td><button className="btn btn-ghost btn-sm" onClick={() => remove(qs.skill.id)}><X size={14} /></button></td>
                    )}
                  </tr>
                ))}
                {queueSkills.length === 0 && <tr><td colSpan={canWrite ? 3 : 2} className="table-empty">Sem skill exigida — todo agente é elegível.</td></tr>}
              </tbody>
            </table>
          </div>
          {canWrite && (
            <>
              <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                <select className="form-input" value={addSkillId} onChange={e => setAddSkillId(e.target.value)}>
                  <option value="">— Selecione uma skill —</option>
                  {availableSkills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
                <input type="number" min={1} max={5} className="form-input" style={{ width: 90 }}
                  placeholder="Nível mínimo" value={addMinLevel} onChange={e => setAddMinLevel(e.target.value)} />
                <button className="btn btn-primary" disabled={!addSkillId} onClick={add}><Plus size={14} /> Exigir</button>
              </div>
              <div style={{ marginTop: 16, paddingTop: 16, borderTop: '1px solid var(--border-color)' }}>
                <button className="btn btn-primary" disabled={recalculating} onClick={recalculate}>
                  <RefreshCw size={14} /> {recalculating ? 'Recalculando…' : 'Recalcular participação'}
                </button>
                <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 8 }}>
                  Ação manual: adiciona quem passou a ser elegível e remove quem deixou de ser —
                  nunca roda sozinha, e nunca altera a prioridade (penalty) de quem continuar
                  membro.
                </p>
              </div>
            </>
          )}
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}
