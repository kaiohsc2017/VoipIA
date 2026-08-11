import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, History, Settings } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import { FlowEditor } from './flow/FlowEditor';
import { VersionHistoryModal } from './flow/VersionHistoryModal';
import type { FlowRequest, FlowView } from '../api/types';

const EMPTY_FORM: FlowRequest = { name: '', description: '', channel: 'voice', entryExtension: '' };

const CHANNEL_LABEL: Record<string, string> = { voice: 'Voz', chat: 'Chat', both: 'Voz + Chat' };

export function FluxosTab({ canWrite }: { canWrite: boolean }) {
  const [flows, setFlows] = useState<FlowView[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<FlowView | null>(null);
  const [confirmFlow, setConfirmFlow] = useState<FlowView | null>(null);
  const [historyOf, setHistoryOf] = useState<FlowView | null>(null);
  const [openEditorFor, setOpenEditorFor] = useState<FlowView | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<FlowRequest>(EMPTY_FORM);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    api.get<FlowView[]>('/callcenter/fluxos')
      .then(({ data }) => setFlows(data))
      .catch(() => setFlows([]));
  };
  useEffect(load, []);

  const openForm = (f: FlowView | null) => {
    setEditing(f);
    setFd(f ? { name: f.name, description: f.description ?? '', channel: f.channel, entryExtension: f.entryExtension ?? '' } : EMPTY_FORM);
    setShowForm(true);
  };

  const save = () => {
    const body: FlowRequest = { ...fd, entryExtension: fd.entryExtension || undefined };
    const req = editing
      ? api.put<FlowView>(`/callcenter/fluxos/${editing.id}`, body)
      : api.post<FlowView>('/callcenter/fluxos', body);
    req.then(({ data }) => { load(); setShowForm(false); setEditing(null); if (!editing) setOpenEditorFor(data); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar fluxo.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/fluxos/${id}`)
      .then(() => setFlows(list => list.filter(f => f.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover fluxo — fluxos com versão publicada não podem ser excluídos.')));
  };

  if (openEditorFor) {
    return (
      <FlowEditor
        flow={openEditorFor}
        canWrite={canWrite}
        onBack={() => { setOpenEditorFor(null); load(); }}
      />
    );
  }

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Fluxos</h1><p>Flow Builder — desenhe URAs visualmente e publique para execução real em uma ligação</p></div>
          {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Novo fluxo</button>}
        </div>
      </div>
      <div className="page-body">
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmFlow && (
          <ConfirmModal
            message={`Remover o fluxo "${confirmFlow.name}"?`}
            onConfirm={() => { del(confirmFlow.id); setConfirmFlow(null); }}
            onCancel={() => setConfirmFlow(null)}
          />
        )}
        {historyOf && (
          <VersionHistoryModal
            flow={historyOf}
            canWrite={canWrite}
            onClose={() => { setHistoryOf(null); load(); }}
          />
        )}

        {canWrite && showForm && (
          <div className="modal-overlay" onClick={() => setShowForm(false)}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>{editing ? 'Editar Fluxo' : 'Novo Fluxo'}</h2>
                <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
              </div>
              <div className="modal-body">
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">Nome</label>
                    <input className="form-input" value={fd.name} onChange={e => setFd(f => ({ ...f, name: e.target.value }))} />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Descrição</label>
                    <input className="form-input" value={fd.description ?? ''} onChange={e => setFd(f => ({ ...f, description: e.target.value }))} />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Canal</label>
                    <select className="form-input" value={fd.channel} onChange={e => setFd(f => ({ ...f, channel: e.target.value as FlowRequest['channel'] }))}>
                      <option value="voice">Voz</option>
                      <option value="chat">Chat</option>
                      <option value="both">Voz + Chat</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <label className="form-label">Ramal de entrada (6000-6999)</label>
                    <input
                      className="form-input"
                      placeholder="ex: 6001"
                      value={fd.entryExtension ?? ''}
                      onChange={e => setFd(f => ({ ...f, entryExtension: e.target.value }))}
                    />
                  </div>
                </div>
                <p style={{ color: 'var(--text-muted)', fontSize: 12, marginTop: 8 }}>
                  Publicar um fluxo com ramal exige que o motor de execução (Fase 5b) já saiba rodar
                  todos os nós usados — nesta sub-fase nenhum nó ainda é executável.
                </p>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
                <button className="btn btn-primary" onClick={save} disabled={!fd.name.trim()}>Salvar</button>
              </div>
            </div>
          </div>
        )}

        <div className="table-wrapper">
          <table>
            <thead>
              <tr><th>Nome</th><th>Canal</th><th>Ramal</th><th>Status</th>{canWrite && <th></th>}</tr>
            </thead>
            <tbody>
              {flows.map(f => (
                <tr key={f.id}>
                  <td>{f.name}</td>
                  <td>{CHANNEL_LABEL[f.channel] ?? f.channel}</td>
                  <td>{f.entryExtension ?? <span style={{ color: 'var(--text-muted)' }}>—</span>}</td>
                  <td>
                    {f.publishedVersionId
                      ? <span className="badge badge-success">Publicado</span>
                      : <span className="badge badge-gray">Nenhuma versão publicada</span>}
                  </td>
                  {canWrite && (
                    <td>
                      <button className="btn btn-ghost btn-sm" title="Editar fluxo" onClick={() => setOpenEditorFor(f)}><Pencil size={14} /></button>
                      <button className="btn btn-ghost btn-sm" title="Histórico de versões" onClick={() => setHistoryOf(f)}><History size={14} /></button>
                      <button className="btn btn-ghost btn-sm" title="Editar metadado" onClick={() => openForm(f)}><Settings size={14} /></button>
                      <button className="btn btn-ghost btn-sm" title="Excluir" onClick={() => setConfirmFlow(f)}><Trash2 size={14} /></button>
                    </td>
                  )}
                </tr>
              ))}
              {flows.length === 0 && (
                <tr><td colSpan={canWrite ? 5 : 4} className="table-empty">Nenhum fluxo cadastrado.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
