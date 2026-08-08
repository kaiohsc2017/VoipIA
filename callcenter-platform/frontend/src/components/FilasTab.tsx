import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, Users, X } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { BusinessUnit, CcAgent, CcQueue, CcQueueMember, QueueRequest } from '../api/types';

const STRATEGIES = ['ringall', 'leastrecent', 'fewestcalls', 'random', 'rrmemory', 'linear'];

const EMPTY_FORM: QueueRequest = {
  name: '', displayName: '', businessUnitId: null, strategy: 'ringall', timeoutSeconds: 15,
  recordingEnabled: true, consentMessagePath: null,
};

export function FilasTab({ canWrite }: { canWrite: boolean }) {
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [businessUnits, setBusinessUnits] = useState<BusinessUnit[]>([]);
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcQueue | null>(null);
  const [confirmQueue, setConfirmQueue] = useState<CcQueue | null>(null);
  const [membersOf, setMembersOf] = useState<CcQueue | null>(null);
  const [members, setMembers] = useState<CcQueueMember[]>([]);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<QueueRequest>(EMPTY_FORM);

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
  }, []);

  const openForm = (q: CcQueue | null) => {
    setEditing(q);
    setFd(q ? {
      name: q.name, displayName: q.displayName,
      businessUnitId: q.businessUnit?.id ?? null, strategy: q.strategy, timeoutSeconds: q.timeoutSeconds,
      recordingEnabled: q.recordingEnabled, consentMessagePath: q.consentMessagePath ?? null,
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

  const addMember = (agentId: number) => {
    if (!membersOf) return;
    api.post<CcQueueMember>(`/callcenter/filas/${membersOf.id}/membros/${agentId}`)
      .then(({ data }) => setMembers(list => [...list, data]))
      .catch(err => flash(getErrorMessage(err, 'Erro ao adicionar agente na fila.')));
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
                    <label className="form-label">
                      <input type="checkbox" checked={fd.recordingEnabled ?? true}
                        onChange={e => setFd(f => ({ ...f, recordingEnabled: e.target.checked }))} />
                      {' '}Gravar chamadas desta fila
                    </label>
                  </div>
                  <div className="form-group">
                    <label className="form-label">Áudio de aviso de gravação (caminho no volume)</label>
                    <input className="form-input" placeholder="/opt/gravacoes/audio/avisos/consentimento.wav"
                      value={fd.consentMessagePath ?? ''}
                      onChange={e => setFd(f => ({ ...f, consentMessagePath: e.target.value || null }))} />
                  </div>
                </div>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
                <button className="btn btn-primary" onClick={save} disabled={!fd.name.trim() || !fd.displayName.trim()}>Salvar</button>
              </div>
            </div>
          </div>
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
                    <thead><tr><th>Agente</th><th>Ramal</th>{canWrite && <th></th>}</tr></thead>
                    <tbody>
                      {members.map(m => (
                        <tr key={m.agent.id}>
                          <td>{m.agent.name}</td>
                          <td>{m.agent.extension?.extension}</td>
                          {canWrite && <td><button className="btn btn-ghost btn-sm" onClick={() => removeMember(m.agent.id)}><X size={14} /></button></td>}
                        </tr>
                      ))}
                      {members.length === 0 && <tr><td colSpan={canWrite ? 3 : 2} className="table-empty">Nenhum agente nesta fila.</td></tr>}
                    </tbody>
                  </table>
                </div>
                {canWrite && availableAgents.length > 0 && (
                  <div className="form-group" style={{ marginTop: 16 }}>
                    <label className="form-label">Adicionar agente</label>
                    <select className="form-input" value="" onChange={e => { if (e.target.value) addMember(Number(e.target.value)); }}>
                      <option value="">— Selecione —</option>
                      {availableAgents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
                    </select>
                  </div>
                )}
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
