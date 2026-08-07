import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, Eye } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { AgentRequest, BusinessUnit, CcAgent } from '../api/types';

const EMPTY_FORM: AgentRequest = { name: '', userId: null, businessUnitId: null, extension: '' };

export function AgentesTab({ canWrite, canReadRamalSecret }: { canWrite: boolean; canReadRamalSecret: boolean }) {
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [businessUnits, setBusinessUnits] = useState<BusinessUnit[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcAgent | null>(null);
  const [confirmAgent, setConfirmAgent] = useState<CcAgent | null>(null);
  const [secretFor, setSecretFor] = useState<CcAgent | null>(null);
  const [secret, setSecret] = useState('');
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
  }, []);

  const openForm = (a: CcAgent | null) => {
    setEditing(a);
    setFd(a ? {
      name: a.name, userId: a.userId ?? null, businessUnitId: a.businessUnit?.id ?? null,
      extension: a.extension?.extension ?? '',
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
