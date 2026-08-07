import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { CcSkill, SkillRequest } from '../api/types';

const EMPTY_FORM: SkillRequest = { name: '', description: '' };

export function SkillsTab({ canWrite }: { canWrite: boolean }) {
  const [skills, setSkills] = useState<CcSkill[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcSkill | null>(null);
  const [confirmSkill, setConfirmSkill] = useState<CcSkill | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<SkillRequest>(EMPTY_FORM);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    api.get<CcSkill[]>('/callcenter/skills')
      .then(({ data }) => setSkills(data))
      .catch(() => setSkills([]));
  };
  useEffect(load, []);

  const openForm = (s: CcSkill | null) => {
    setEditing(s);
    setFd(s ? { name: s.name, description: s.description ?? '' } : EMPTY_FORM);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/skills/${editing.id}`, fd)
      : api.post('/callcenter/skills', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar skill.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/skills/${id}`)
      .then(() => setSkills(list => list.filter(s => s.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover skill.')));
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Skills</h1><p>Catálogo de habilidades do Call Center — roteamento por skill entra na Fase 5</p></div>
          {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Nova skill</button>}
        </div>
      </div>
      <div className="page-body">
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmSkill && (
          <ConfirmModal
            message={`Remover a skill "${confirmSkill.name}"?`}
            onConfirm={() => { del(confirmSkill.id); setConfirmSkill(null); }}
            onCancel={() => setConfirmSkill(null)}
          />
        )}

        {canWrite && showForm && (
          <div className="modal-overlay" onClick={() => setShowForm(false)}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>{editing ? 'Editar Skill' : 'Nova Skill'}</h2>
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
                </div>
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
              <tr><th>Nome</th><th>Descrição</th>{canWrite && <th></th>}</tr>
            </thead>
            <tbody>
              {skills.map(s => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td>{s.description}</td>
                  {canWrite && (
                    <td>
                      <button className="btn btn-ghost btn-sm" onClick={() => openForm(s)}><Pencil size={14} /></button>
                      <button className="btn btn-ghost btn-sm" onClick={() => setConfirmSkill(s)}><Trash2 size={14} /></button>
                    </td>
                  )}
                </tr>
              ))}
              {skills.length === 0 && (
                <tr><td colSpan={canWrite ? 3 : 2} className="table-empty">Nenhuma skill cadastrada.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
