import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { CcPauseReason, PauseReasonRequest, CcDisposition, DispositionRequest } from '../api/types';

const EMPTY_PAUSE: PauseReasonRequest = { code: '', label: '', productive: false, active: true };
const EMPTY_DISPOSITION: DispositionRequest = { code: '', label: '', active: true };

/**
 * ConfiguracoesTab — CRUD de motivos de pausa e tabulações (Fase 12.6). Até esta entrega só
 * existia o seed da V47/catálogo inicial, sem UI — a tabulação é obrigatória para o agente sair
 * do ACW (Fase 4), então sem esta tela a operação real ficava presa ao seed.
 */
export function ConfiguracoesTab({ canWrite }: { canWrite: boolean }) {
  return (
    <>
      <div className="page-header">
        <h1>Configurações do Call Center</h1>
        <p>Motivos de pausa (Desktop do Agente) e tabulações (encerramento do ACW)</p>
      </div>
      <div className="page-body">
        <PauseReasonsSection canWrite={canWrite} />
        <div style={{ height: 32 }} />
        <DispositionsSection canWrite={canWrite} />
      </div>
    </>
  );
}

function PauseReasonsSection({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<CcPauseReason[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcPauseReason | null>(null);
  const [confirmItem, setConfirmItem] = useState<CcPauseReason | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<PauseReasonRequest>(EMPTY_PAUSE);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const load = () => {
    api.get<CcPauseReason[]>('/callcenter/pause-reasons')
      .then(({ data }) => setItems(data))
      .catch(() => setItems([]));
  };
  useEffect(load, []);

  const openForm = (r: CcPauseReason | null) => {
    setEditing(r);
    setFd(r ? { code: r.code, label: r.label, productive: r.productive, active: r.active } : EMPTY_PAUSE);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/pause-reasons/${editing.id}`, fd)
      : api.post('/callcenter/pause-reasons', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar motivo de pausa.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/pause-reasons/${id}`)
      .then(() => setItems(list => list.filter(r => r.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover motivo de pausa.')));
  };

  return (
    <div>
      <div className="flex items-center justify-between">
        <h2 style={{ margin: 0 }}>Motivos de pausa</h2>
        {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Novo motivo</button>}
      </div>
      {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
      {confirmItem && (
        <ConfirmModal
          message={`Remover o motivo de pausa "${confirmItem.label}"?`}
          onConfirm={() => { del(confirmItem.id); setConfirmItem(null); }}
          onCancel={() => setConfirmItem(null)}
        />
      )}
      {canWrite && showForm && (
        <div className="modal-overlay" onClick={() => setShowForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editing ? 'Editar Motivo de Pausa' : 'Novo Motivo de Pausa'}</h2>
              <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Código</label>
                  <input className="form-input" value={fd.code} onChange={e => setFd(f => ({ ...f, code: e.target.value.toUpperCase() }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Rótulo</label>
                  <input className="form-input" value={fd.label} onChange={e => setFd(f => ({ ...f, label: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input type="checkbox" checked={!!fd.productive} onChange={e => setFd(f => ({ ...f, productive: e.target.checked }))} />
                    Produtiva
                  </label>
                </div>
                <div className="form-group">
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input type="checkbox" checked={fd.active !== false} onChange={e => setFd(f => ({ ...f, active: e.target.checked }))} />
                    Ativo
                  </label>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={!fd.code.trim() || !fd.label.trim()}>Salvar</button>
            </div>
          </div>
        </div>
      )}
      <div className="table-wrapper">
        <table>
          <thead><tr><th>Código</th><th>Rótulo</th><th>Produtiva</th><th>Ativo</th>{canWrite && <th></th>}</tr></thead>
          <tbody>
            {items.map(r => (
              <tr key={r.id}>
                <td>{r.code}</td>
                <td>{r.label}</td>
                <td>{r.productive ? 'Sim' : 'Não'}</td>
                <td>{r.active ? 'Sim' : 'Não'}</td>
                {canWrite && (
                  <td>
                    <button className="btn btn-ghost btn-sm" onClick={() => openForm(r)}><Pencil size={14} /></button>
                    <button className="btn btn-ghost btn-sm" onClick={() => setConfirmItem(r)}><Trash2 size={14} /></button>
                  </td>
                )}
              </tr>
            ))}
            {items.length === 0 && <tr><td colSpan={canWrite ? 5 : 4} className="table-empty">Nenhum motivo de pausa cadastrado.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function DispositionsSection({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<CcDisposition[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcDisposition | null>(null);
  const [confirmItem, setConfirmItem] = useState<CcDisposition | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<DispositionRequest>(EMPTY_DISPOSITION);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const load = () => {
    api.get<CcDisposition[]>('/callcenter/dispositions')
      .then(({ data }) => setItems(data))
      .catch(() => setItems([]));
  };
  useEffect(load, []);

  const openForm = (d: CcDisposition | null) => {
    setEditing(d);
    setFd(d ? { code: d.code, label: d.label, active: d.active } : EMPTY_DISPOSITION);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/dispositions/${editing.id}`, fd)
      : api.post('/callcenter/dispositions', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar tabulação.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/dispositions/${id}`)
      .then(() => setItems(list => list.filter(d => d.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover tabulação.')));
  };

  return (
    <div>
      <div className="flex items-center justify-between">
        <h2 style={{ margin: 0 }}>Tabulações</h2>
        {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Nova tabulação</button>}
      </div>
      {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
      {confirmItem && (
        <ConfirmModal
          message={`Remover a tabulação "${confirmItem.label}"?`}
          onConfirm={() => { del(confirmItem.id); setConfirmItem(null); }}
          onCancel={() => setConfirmItem(null)}
        />
      )}
      {canWrite && showForm && (
        <div className="modal-overlay" onClick={() => setShowForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editing ? 'Editar Tabulação' : 'Nova Tabulação'}</h2>
              <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Código</label>
                  <input className="form-input" value={fd.code} onChange={e => setFd(f => ({ ...f, code: e.target.value.toUpperCase() }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Rótulo</label>
                  <input className="form-input" value={fd.label} onChange={e => setFd(f => ({ ...f, label: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input type="checkbox" checked={fd.active !== false} onChange={e => setFd(f => ({ ...f, active: e.target.checked }))} />
                    Ativo
                  </label>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={!fd.code.trim() || !fd.label.trim()}>Salvar</button>
            </div>
          </div>
        </div>
      )}
      <div className="table-wrapper">
        <table>
          <thead><tr><th>Código</th><th>Rótulo</th><th>Ativo</th>{canWrite && <th></th>}</tr></thead>
          <tbody>
            {items.map(d => (
              <tr key={d.id}>
                <td>{d.code}</td>
                <td>{d.label}</td>
                <td>{d.active ? 'Sim' : 'Não'}</td>
                {canWrite && (
                  <td>
                    <button className="btn btn-ghost btn-sm" onClick={() => openForm(d)}><Pencil size={14} /></button>
                    <button className="btn btn-ghost btn-sm" onClick={() => setConfirmItem(d)}><Trash2 size={14} /></button>
                  </td>
                )}
              </tr>
            ))}
            {items.length === 0 && <tr><td colSpan={canWrite ? 4 : 3} className="table-empty">Nenhuma tabulação cadastrada.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}
