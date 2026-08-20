import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, Eye, ListOrdered, Star, Calendar } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { AgentRequest, AppUserOption, BusinessUnit, CcAgent, CcAgentSkill, CcQueue, CcQueueMember, CcSkill } from '../api/types';

const EMPTY_FORM: AgentRequest = { name: '', userId: null, businessUnitId: null, extension: '', maxConcurrentChats: null };

export function AgentesTab({ canWrite, canReadRamalSecret }: { canWrite: boolean; canReadRamalSecret: boolean }) {
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [businessUnits, setBusinessUnits] = useState<BusinessUnit[]>([]);
  const [users, setUsers] = useState<AppUserOption[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcAgent | null>(null);
  const [confirmAgent, setConfirmAgent] = useState<CcAgent | null>(null);
  const [secretFor, setSecretFor] = useState<CcAgent | null>(null);
  const [secret, setSecret] = useState('');
  const [queuesOf, setQueuesOf] = useState<CcAgent | null>(null);
  const [skillsOf, setSkillsOf] = useState<CcAgent | null>(null);
  const [schedulesOf, setSchedulesOf] = useState<CcAgent | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<AgentRequest>(EMPTY_FORM);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    api.get<CcAgent[]>('/callcenter/agentes')
      .then(({ data }) => setAgents(data))
      .catch(() => setAgents([]));
  };
  useEffect(() => {
    load();
    api.get<BusinessUnit[]>('/business-units?active=true').then(({ data }) => setBusinessUnits(data)).catch(() => setBusinessUnits([]));
    // Sem telecom.users (grupo customizado sem essa permissão) cai silenciosamente para o
    // campo numérico manual — o formulário não trava, só perde a conveniência da busca.
    api.get<AppUserOption[]>('/users').then(({ data }) => setUsers(data)).catch(() => setUsers([]));
  }, []);

  const openForm = (a: CcAgent | null) => {
    setEditing(a);
    setFd(a ? {
      name: a.name, userId: a.userId ?? null, businessUnitId: a.businessUnit?.id ?? null,
      extension: a.extension?.extension ?? '', maxConcurrentChats: a.maxConcurrentChats ?? null,
    } : EMPTY_FORM);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/agentes/${editing.id}`, fd)
      : api.post('/callcenter/agentes', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar agente.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/agentes/${id}`)
      .then(() => setAgents(list => list.filter(a => a.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover agente.')));
  };

  const revealSecret = (a: CcAgent) => {
    setSecretFor(a);
    setSecret('');
    api.get<{ secret: string }>(`/callcenter/agentes/${a.id}/ramal-secret`)
      .then(({ data }) => setSecret(data.secret))
      .catch(err => { flash(getErrorMessage(err, 'Erro ao obter a senha do ramal.')); setSecretFor(null); });
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Agentes</h1><p>Agentes e ramais SIP do Call Center (provisionamento Realtime)</p></div>
          {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Novo agente</button>}
        </div>
      </div>
      <div className="page-body">
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmAgent && (
          <ConfirmModal
            message={`Remover o agente "${confirmAgent.name}" e desprovisionar seu ramal?`}
            onConfirm={() => { del(confirmAgent.id); setConfirmAgent(null); }}
            onCancel={() => setConfirmAgent(null)}
          />
        )}

        {secretFor && (
          <div className="modal-overlay" onClick={() => setSecretFor(null)}>
            <div className="modal modal-sm" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>Senha do ramal {secretFor.extension?.extension}</h2>
                <button className="btn-close" onClick={() => setSecretFor(null)}>×</button>
              </div>
              <div className="modal-body">
                <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginBottom: 8 }}>
                  Use esta senha para registrar o softphone do agente "{secretFor.name}".
                </p>
                <code style={{ display: 'block', padding: 12, background: 'var(--bg-secondary)', borderRadius: 8, fontSize: 14 }}>
                  {secret || 'Carregando…'}
                </code>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setSecretFor(null)}>Fechar</button>
              </div>
            </div>
          </div>
        )}

        {queuesOf && (
          <AgentQueuesModal agent={queuesOf} canWrite={canWrite} onClose={() => setQueuesOf(null)}
            onError={m => flash(m)} />
        )}

        {skillsOf && (
          <AgentSkillsModal agent={skillsOf} canWrite={canWrite} onClose={() => setSkillsOf(null)}
            onError={m => flash(m)} />
        )}

        {schedulesOf && (
          <AgentScheduleModal agent={schedulesOf} canWrite={canWrite} onClose={() => setSchedulesOf(null)}
            onError={m => flash(m)} />
        )}

        {canWrite && showForm && (
          <div className="modal-overlay" onClick={() => setShowForm(false)}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>{editing ? 'Editar Agente' : 'Novo Agente'}</h2>
                <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
              </div>
              <div className="modal-body">
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">Nome</label>
                    <input className="form-input" value={fd.name} onChange={e => setFd(f => ({ ...f, name: e.target.value }))} />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Ramal (faixa 4000-4999)</label>
                    <input className="form-input" value={fd.extension} disabled={!!editing}
                      onChange={e => setFd(f => ({ ...f, extension: e.target.value }))} />
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
                    <label className="form-label">Usuário vinculado (opcional)</label>
                    {users.length > 0 ? (
                      <select className="form-input" value={fd.userId ?? ''}
                        onChange={e => setFd(f => ({ ...f, userId: e.target.value ? Number(e.target.value) : null }))}>
                        <option value="">— Nenhum —</option>
                        {users.map(u => <option key={u.id} value={u.id}>{u.displayName} ({u.username})</option>)}
                      </select>
                    ) : (
                      <input className="form-input" type="number" placeholder="ID do usuário (opcional)"
                        value={fd.userId ?? ''}
                        onChange={e => setFd(f => ({ ...f, userId: e.target.value ? Number(e.target.value) : null }))} />
                    )}
                  </div>
                  <div className="form-group">
                    <label className="form-label" htmlFor="agente-max-concurrent-chats">
                      Limite de chats simultâneos (Fase 7c)
                    </label>
                    <input id="agente-max-concurrent-chats" type="number" min={0} className="form-input"
                      placeholder="Usar limite da fila"
                      value={fd.maxConcurrentChats ?? ''}
                      onChange={e => setFd(f => ({ ...f, maxConcurrentChats: e.target.value ? Number(e.target.value) : null }))} />
                    <span style={{ fontSize: '0.78rem', opacity: 0.7 }}>
                      Vazio ou zero = usa o limite configurado na fila. Qualquer valor maior que zero prevalece sobre o da fila.
                    </span>
                  </div>
                </div>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
                <button className="btn btn-primary" onClick={save} disabled={!fd.name.trim() || !fd.extension.trim()}>Salvar</button>
              </div>
            </div>
          </div>
        )}

        <div className="table-wrapper">
          <table>
            <thead>
              <tr><th>Nome</th><th>Ramal</th><th>BU</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              {agents.map(a => (
                <tr key={a.id}>
                  <td>{a.name}</td>
                  <td>{a.extension?.extension ?? '—'}</td>
                  <td>{a.businessUnit?.name ?? '—'}</td>
                  <td><span className={`badge ${a.active ? 'badge-success' : 'badge-gray'}`}>{a.active ? 'Ativo' : 'Inativo'}</span></td>
                  <td>
                    <button className="btn btn-ghost btn-sm" title="Filas do agente" onClick={() => setQueuesOf(a)}><ListOrdered size={14} /></button>
                    <button className="btn btn-ghost btn-sm" title="Skills do agente" onClick={() => setSkillsOf(a)}><Star size={14} /></button>
                    <button className="btn btn-ghost btn-sm" title="Escala de trabalho (Turnos)" onClick={() => setSchedulesOf(a)}><Calendar size={14} /></button>
                    {canReadRamalSecret && a.extension && (
                      <button className="btn btn-ghost btn-sm" title="Ver senha do ramal" onClick={() => revealSecret(a)}><Eye size={14} /></button>
                    )}
                    {canWrite && (
                      <>
                        <button className="btn btn-ghost btn-sm" onClick={() => openForm(a)}><Pencil size={14} /></button>
                        <button className="btn btn-ghost btn-sm" onClick={() => setConfirmAgent(a)}><Trash2 size={14} /></button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {agents.length === 0 && <tr><td colSpan={5} className="table-empty">Nenhum agente cadastrado.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

/**
 * AgentQueuesModal — filas de um agente (Fase 12.4): listar, adicionar, remover e editar
 * prioridade. Espelha o modal de membros de FilasTab.tsx, na direção inversa (agente → filas).
 */
function AgentQueuesModal({ agent, canWrite, onClose, onError }: {
  agent: CcAgent; canWrite: boolean; onClose: () => void; onError: (m: string) => void;
}) {
  const [memberships, setMemberships] = useState<CcQueueMember[]>([]);
  const [allQueues, setAllQueues] = useState<CcQueue[]>([]);
  const [addQueueId, setAddQueueId] = useState('');
  const [addPriority, setAddPriority] = useState('0');

  const load = () => {
    api.get<CcQueueMember[]>(`/callcenter/agentes/${agent.id}/filas`)
      .then(({ data }) => setMemberships(data))
      .catch(err => onError(getErrorMessage(err, 'Erro ao listar filas do agente.')));
  };
  useEffect(() => {
    load();
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setAllQueues(data)).catch(() => setAllQueues([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id]);

  const availableQueues = allQueues.filter(q => !memberships.some(m => m.queue.id === q.id));

  const add = () => {
    if (!addQueueId) return;
    api.post(`/callcenter/agentes/${agent.id}/filas/${addQueueId}`, { penalty: Number(addPriority) || 0 })
      .then(() => { load(); setAddQueueId(''); setAddPriority('0'); })
      .catch(err => onError(getErrorMessage(err, 'Erro ao adicionar à fila.')));
  };

  const updatePriority = (queueId: number, penalty: number) => {
    api.put(`/callcenter/agentes/${agent.id}/filas/${queueId}/prioridade`, { penalty })
      .then(load)
      .catch(err => onError(getErrorMessage(err, 'Erro ao atualizar prioridade.')));
  };

  const remove = (queueId: number) => {
    api.delete(`/callcenter/agentes/${agent.id}/filas/${queueId}`)
      .then(load)
      .catch(err => onError(getErrorMessage(err, 'Erro ao remover da fila.')));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Filas de "{agent.name}"</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          <div className="table-wrapper">
            <table>
              <thead><tr><th>Fila</th><th>Prioridade</th>{canWrite && <th></th>}</tr></thead>
              <tbody>
                {memberships.map(m => (
                  <tr key={m.id}>
                    <td>{m.queue.displayName} ({m.queue.name})</td>
                    <td>
                      {canWrite ? (
                        <input type="number" min={0} className="form-input" style={{ width: 70 }}
                          defaultValue={m.penalty}
                          onBlur={e => {
                            const v = Number(e.target.value);
                            if (!Number.isNaN(v) && v !== m.penalty) updatePriority(m.queue.id, v);
                          }} />
                      ) : m.penalty}
                    </td>
                    {canWrite && (
                      <td><button className="btn btn-ghost btn-sm" onClick={() => remove(m.queue.id)}><Trash2 size={14} /></button></td>
                    )}
                  </tr>
                ))}
                {memberships.length === 0 && <tr><td colSpan={canWrite ? 3 : 2} className="table-empty">Agente não está em nenhuma fila.</td></tr>}
              </tbody>
            </table>
          </div>
          {canWrite && (
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <select className="form-input" value={addQueueId} onChange={e => setAddQueueId(e.target.value)}>
                <option value="">— Selecione uma fila —</option>
                {availableQueues.map(q => <option key={q.id} value={q.id}>{q.displayName} ({q.name})</option>)}
              </select>
              <input type="number" min={0} className="form-input" style={{ width: 90 }}
                placeholder="Prioridade" value={addPriority} onChange={e => setAddPriority(e.target.value)} />
              <button className="btn btn-primary" disabled={!addQueueId} onClick={add}><Plus size={14} /> Adicionar</button>
            </div>
          )}
          <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 8 }}>
            Prioridade: menor valor é atendido antes (mesma semântica do Asterisk).
          </p>
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}

/**
 * AgentSkillsModal — skills do agente com nível 1-5 (Fase 5f.1). Nunca mexe em prioridade
 * (penalty) de fila — skill e prioridade manual são coisas independentes, ver
 * CallCenterSkillRoutingService no backend.
 */
function AgentSkillsModal({ agent, canWrite, onClose, onError }: {
  agent: CcAgent; canWrite: boolean; onClose: () => void; onError: (m: string) => void;
}) {
  const [agentSkills, setAgentSkills] = useState<CcAgentSkill[]>([]);
  const [allSkills, setAllSkills] = useState<CcSkill[]>([]);
  const [addSkillId, setAddSkillId] = useState('');
  const [addLevel, setAddLevel] = useState('1');

  const load = () => {
    api.get<CcAgentSkill[]>(`/callcenter/agentes/${agent.id}/skills`)
      .then(({ data }) => setAgentSkills(data))
      .catch(err => onError(getErrorMessage(err, 'Erro ao listar skills do agente.')));
  };
  useEffect(() => {
    load();
    api.get<CcSkill[]>('/callcenter/skills').then(({ data }) => setAllSkills(data)).catch(() => setAllSkills([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id]);

  const availableSkills = allSkills.filter(s => !agentSkills.some(as => as.skill.id === s.id));

  const add = () => {
    if (!addSkillId) return;
    api.put(`/callcenter/agentes/${agent.id}/skills/${addSkillId}`, { level: Number(addLevel) || 1 })
      .then(() => { load(); setAddSkillId(''); setAddLevel('1'); })
      .catch(err => onError(getErrorMessage(err, 'Erro ao atribuir skill.')));
  };

  const updateLevel = (skillId: number, level: number) => {
    api.put(`/callcenter/agentes/${agent.id}/skills/${skillId}`, { level })
      .then(load)
      .catch(err => onError(getErrorMessage(err, 'Erro ao atualizar nível da skill.')));
  };

  const remove = (skillId: number) => {
    api.delete(`/callcenter/agentes/${agent.id}/skills/${skillId}`)
      .then(load)
      .catch(err => onError(getErrorMessage(err, 'Erro ao remover skill do agente.')));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Skills de "{agent.name}"</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          <div className="table-wrapper">
            <table>
              <thead><tr><th>Skill</th><th>Nível (1-5)</th>{canWrite && <th></th>}</tr></thead>
              <tbody>
                {agentSkills.map(as => (
                  <tr key={as.skill.id}>
                    <td>{as.skill.name}</td>
                    <td>
                      {canWrite ? (
                        <input type="number" min={1} max={5} className="form-input" style={{ width: 60 }}
                          defaultValue={as.level}
                          onBlur={e => {
                            const v = Number(e.target.value);
                            if (v >= 1 && v <= 5 && v !== as.level) updateLevel(as.skill.id, v);
                          }} />
                      ) : as.level}
                    </td>
                    {canWrite && (
                      <td><button className="btn btn-ghost btn-sm" onClick={() => remove(as.skill.id)}><Trash2 size={14} /></button></td>
                    )}
                  </tr>
                ))}
                {agentSkills.length === 0 && <tr><td colSpan={canWrite ? 3 : 2} className="table-empty">Agente sem skill cadastrada.</td></tr>}
              </tbody>
            </table>
          </div>
          {canWrite && (
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <select className="form-input" value={addSkillId} onChange={e => setAddSkillId(e.target.value)}>
                <option value="">— Selecione uma skill —</option>
                {availableSkills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
              <input type="number" min={1} max={5} className="form-input" style={{ width: 70 }}
                placeholder="Nível" value={addLevel} onChange={e => setAddLevel(e.target.value)} />
              <button className="btn btn-primary" disabled={!addSkillId} onClick={add}><Plus size={14} /> Adicionar</button>
            </div>
          )}
          <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 8 }}>
            Nível: 1 (iniciante) a 5 (especialista). A skill decide só elegibilidade de participação
            em fila — a prioridade (penalty) da fila continua 100% manual, independente da skill.
          </p>
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}

interface AgentSchedule {
  id: number;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  active: boolean;
}

const DAY_NAMES: Record<number, string> = {
  1: 'Segunda-feira',
  2: 'Terça-feira',
  3: 'Quarta-feira',
  4: 'Quinta-feira',
  5: 'Sexta-feira',
  6: 'Sábado',
  7: 'Domingo',
};

function AgentScheduleModal({ agent, canWrite, onClose, onError }: {
  agent: CcAgent; canWrite: boolean; onClose: () => void; onError: (m: string) => void;
}) {
  const [schedules, setSchedules] = useState<AgentSchedule[]>([]);
  const [loading, setLoading] = useState(true);
  const [dayOfWeek, setDayOfWeek] = useState(1);
  const [startTime, setStartTime] = useState('08:00');
  const [endTime, setEndTime] = useState('17:00');
  const [saving, setSaving] = useState(false);

  const load = () => {
    setLoading(true);
    api.get<AgentSchedule[]>(`/callcenter/reports/agent-schedules?agentId=${agent.id}`)
      .then(({ data }) => setSchedules(data || []))
      .catch(err => onError(getErrorMessage(err, 'Erro ao carregar escalas do agente.')))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id]);

  const handleAdd = () => {
    if (!startTime || !endTime) {
      onError('Informe o horário de entrada e saída.');
      return;
    }
    setSaving(true);
    api.post('/callcenter/reports/agent-schedules', {
      agentId: agent.id,
      dayOfWeek: Number(dayOfWeek),
      startTime: startTime.length === 5 ? `${startTime}:00` : startTime,
      endTime: endTime.length === 5 ? `${endTime}:00` : endTime,
    })
      .then(() => {
        load();
      })
      .catch(err => onError(getErrorMessage(err, 'Erro ao salvar escala de trabalho.')))
      .finally(() => setSaving(false));
  };

  const handleDelete = (id: number) => {
    api.delete(`/callcenter/reports/agent-schedules/${id}`)
      .then(() => load())
      .catch(err => onError(getErrorMessage(err, 'Erro ao remover escala.')));
  };

  const handleApplyWeekdays = () => {
    setSaving(true);
    const promises = [1, 2, 3, 4, 5].map(d =>
      api.post('/callcenter/reports/agent-schedules', {
        agentId: agent.id,
        dayOfWeek: d,
        startTime: startTime.length === 5 ? `${startTime}:00` : startTime,
        endTime: endTime.length === 5 ? `${endTime}:00` : endTime,
      })
    );
    Promise.all(promises)
      .then(() => load())
      .catch(err => onError(getErrorMessage(err, 'Erro ao salvar escala semanal.')))
      .finally(() => setSaving(false));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal-lg" onClick={e => e.stopPropagation()} style={{ maxWidth: '680px' }}>
        <div className="modal-header">
          <h2>📅 Escala de Trabalho — {agent.name}</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', margin: 0 }}>
            Configure os horários esperados de login (entrada) e logout (saída) para cada dia da semana.
          </p>

          {canWrite && (
            <div style={{ background: 'var(--bg-deep, #f8f9fa)', padding: '14px', borderRadius: '10px', border: '1px solid var(--border-glass, #e5e7eb)' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr 1fr auto', gap: '10px', alignItems: 'end' }}>
                <div>
                  <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                    Dia da Semana
                  </label>
                  <select
                    className="form-input"
                    value={dayOfWeek}
                    onChange={e => setDayOfWeek(Number(e.target.value))}
                    style={{ width: '100%' }}
                  >
                    {Object.entries(DAY_NAMES).map(([d, name]) => (
                      <option key={d} value={d}>{name}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                    Login (Entrada)
                  </label>
                  <input
                    type="time"
                    className="form-input"
                    value={startTime}
                    onChange={e => setStartTime(e.target.value)}
                    style={{ width: '100%' }}
                  />
                </div>
                <div>
                  <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                    Logout (Saída)
                  </label>
                  <input
                    type="time"
                    className="form-input"
                    value={endTime}
                    onChange={e => setEndTime(e.target.value)}
                    style={{ width: '100%' }}
                  />
                </div>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={handleAdd}
                  disabled={saving}
                  style={{ height: '36px', padding: '0 14px' }}
                >
                  {saving ? '...' : '+ Adicionar'}
                </button>
              </div>

              <div style={{ marginTop: '10px', display: 'flex', justifyContent: 'flex-end' }}>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={handleApplyWeekdays}
                  disabled={saving}
                  style={{ fontSize: '0.75rem', color: 'var(--clr-primary)' }}
                >
                  ⚡ Replicar horário para Seg–Sex
                </button>
              </div>
            </div>
          )}

          <div className="table-wrapper" style={{ maxHeight: '300px', overflowY: 'auto' }}>
            <table style={{ width: '100%' }}>
              <thead>
                <tr>
                  <th>Dia da Semana</th>
                  <th>Horário Entrada (Login)</th>
                  <th>Horário Saída (Logout)</th>
                  <th>Carga Diária</th>
                  {canWrite && <th style={{ width: '50px' }}></th>}
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={5} style={{ textAlign: 'center', padding: '20px' }}>Carregando escalas...</td></tr>
                ) : schedules.length === 0 ? (
                  <tr><td colSpan={5} style={{ textAlign: 'center', padding: '20px', color: 'var(--text-muted)' }}>Nenhuma escala cadastrada para este agente.</td></tr>
                ) : (
                  schedules
                    .sort((a, b) => a.dayOfWeek - b.dayOfWeek)
                    .map(s => {
                      const [sh, sm] = s.startTime.split(':').map(Number);
                      const [eh, em] = s.endTime.split(':').map(Number);
                      const totalMin = (eh * 60 + em) - (sh * 60 + sm);
                      const hours = Math.floor(totalMin / 60);
                      const mins = totalMin % 60;
                      return (
                        <tr key={s.id}>
                          <td style={{ fontWeight: 600 }}>{DAY_NAMES[s.dayOfWeek]}</td>
                          <td style={{ fontFamily: 'monospace' }}>{s.startTime.slice(0, 5)}</td>
                          <td style={{ fontFamily: 'monospace' }}>{s.endTime.slice(0, 5)}</td>
                          <td style={{ color: 'var(--text-muted)' }}>{hours}h {mins > 0 ? `${mins}m` : ''}</td>
                          {canWrite && (
                            <td>
                              <button
                                className="btn btn-ghost btn-sm"
                                onClick={() => handleDelete(s.id)}
                                title="Remover escala"
                                style={{ color: 'var(--clr-danger)' }}
                              >
                                <Trash2 size={13} />
                              </button>
                            </td>
                          )}
                        </tr>
                      );
                    })
                )}
              </tbody>
            </table>
          </div>
        </div>
        <div className="modal-footer">
          <button className="btn btn-primary" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}
