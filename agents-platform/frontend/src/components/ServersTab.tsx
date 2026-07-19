import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { PaginatedResponse, ServerEntry, ServerTestResult } from '../api/types';

const EMPTY_FORM: Omit<ServerEntry, 'id'> = {
  name: '', host: '', port: 22, username: '', auth_type: 'password', password: '', ssh_key: '', tags: [],
};

type TestState = 'testing' | 'ok' | 'fail';

export function ServersTab({ canWrite }: { canWrite: boolean }) {
  const [servers, setServers] = useState<ServerEntry[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<ServerEntry | null>(null);
  const [confirmServer, setConfirmServer] = useState<ServerEntry | null>(null);
  const [testing, setTesting] = useState<Record<string, TestState>>({});
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<Omit<ServerEntry, 'id'>>(EMPTY_FORM);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const setF = <K extends keyof typeof fd>(k: K, v: (typeof fd)[K]) => setFd(f => ({ ...f, [k]: v }));

  const load = () => {
    api.get<PaginatedResponse<ServerEntry> | ServerEntry[]>('/api/servers/?limit=200')
      .then(({ data }) => setServers(Array.isArray(data) ? data : data.items))
      .catch(() => setServers([]));
  };
  useEffect(load, []);

  const openForm = (s: ServerEntry | null) => { setEditing(s); setFd(s ?? EMPTY_FORM); setShowForm(true); };

  const save = () => {
    const req = editing ? api.put(`/api/servers/${editing.id}`, fd) : api.post('/api/servers/', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar servidor.')));
  };

  const del = (id: string) => {
    api.delete(`/api/servers/${id}`)
      .then(() => setServers(list => list.filter(x => x.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover servidor.')));
  };

  const testConn = (id: string) => {
    setTesting(t => ({ ...t, [id]: 'testing' }));
    api.post<ServerTestResult>(`/api/servers/${id}/test`, {})
      .then(({ data }) => setTesting(t => ({ ...t, [id]: data?.ok ? 'ok' : 'fail' })))
      .catch(() => setTesting(t => ({ ...t, [id]: 'fail' })));
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Servidores SSH</h1><p>Alvos de teste dos agentes do tipo SSH</p></div>
          {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Novo servidor</button>}
        </div>
      </div>
      <div className="page-body">
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmServer && (
          <ConfirmModal
            message={`Remover "${confirmServer.name}"?`}
            onConfirm={() => { del(confirmServer.id); setConfirmServer(null); }}
            onCancel={() => setConfirmServer(null)}
          />
        )}

        {canWrite && showForm && (
          <div className="modal-overlay">
            <div className="modal">
              <div className="modal-header">
                <h2>{editing ? 'Editar Servidor' : 'Novo Servidor'}</h2>
                <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
              </div>
              <div className="modal-body">
                <div className="form-grid">
                  <div className="form-group"><label className="form-label">Nome</label>
                    <input className="form-input" value={fd.name} onChange={e => setF('name', e.target.value)} placeholder="Prod-01" /></div>
                  <div className="form-group"><label className="form-label">Host</label>
                    <input className="form-input" value={fd.host} onChange={e => setF('host', e.target.value)} placeholder="192.168.1.100" /></div>
                </div>
                <div className="form-grid">
                  <div className="form-group"><label className="form-label">Porta</label>
                    <input className="form-input" type="number" value={fd.port} onChange={e => setF('port', Number(e.target.value))} /></div>
                  <div className="form-group"><label className="form-label">Usuário</label>
                    <input className="form-input" value={fd.username} onChange={e => setF('username', e.target.value)} placeholder="root" /></div>
                </div>
                <div className="form-group"><label className="form-label">Autenticação</label>
                  <select className="form-select" value={fd.auth_type} onChange={e => setF('auth_type', e.target.value as ServerEntry['auth_type'])}>
                    <option value="password">Senha</option>
                    <option value="key">Chave SSH</option>
                  </select>
                </div>
                {fd.auth_type === 'password' ? (
                  <div className="form-group"><label className="form-label">Senha</label>
                    <input className="form-input" type="password" value={fd.password ?? ''} onChange={e => setF('password', e.target.value)} /></div>
                ) : (
                  <div className="form-group"><label className="form-label">Chave privada (PEM)</label>
                    <textarea className="form-textarea" value={fd.ssh_key ?? ''} onChange={e => setF('ssh_key', e.target.value)} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" /></div>
                )}
                <div className="form-group"><label className="form-label">Tags (vírgula)</label>
                  <input className="form-input" value={(fd.tags ?? []).join(',')}
                    onChange={e => setF('tags', e.target.value.split(',').map(t => t.trim()).filter(Boolean))} />
                </div>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
                <button className="btn btn-primary" onClick={save}>{editing ? 'Salvar' : 'Adicionar'}</button>
              </div>
            </div>
          </div>
        )}

        <div className="card">
          <div className="table-wrapper">
            <table>
              <thead><tr><th>Nome</th><th>Host</th><th>Usuário</th><th>Auth</th><th>Tags</th><th>Ações</th></tr></thead>
              <tbody>
                {servers.length === 0 ? (
                  <tr><td colSpan={6} className="table-empty">Nenhum servidor cadastrado</td></tr>
                ) : servers.map(s => (
                  <tr key={s.id}>
                    <td style={{ fontWeight: 500 }}>{s.name}</td>
                    <td>{s.host}:{s.port}</td>
                    <td>{s.username}</td>
                    <td><span className="badge badge-gray">{s.auth_type}</span></td>
                    <td>{(s.tags || []).map(t => <span key={t} className="chip" style={{ marginRight: 4 }}>{t}</span>)}</td>
                    <td>
                      <div className="flex gap-1" style={{ alignItems: 'center' }}>
                        {testing[s.id] === 'testing' && <span className="td-muted" style={{ fontSize: 12 }}>testando...</span>}
                        {testing[s.id] === 'ok' && <span style={{ color: 'var(--clr-success)', fontSize: 12 }}>OK</span>}
                        {testing[s.id] === 'fail' && <span style={{ color: 'var(--clr-danger)', fontSize: 12 }}>Falhou</span>}
                        {canWrite && (
                          <>
                            <button className="btn btn-sm btn-ghost" onClick={() => testConn(s.id)} disabled={testing[s.id] === 'testing'}>Testar</button>
                            <button className="btn btn-sm btn-ghost" onClick={() => openForm(s)}><Pencil size={12} /></button>
                            <button className="btn btn-sm btn-danger" onClick={() => setConfirmServer(s)}><Trash2 size={12} /></button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}
