/**
 * UraManagementTab.tsx — CRUD de URAs (Módulo 1).
 * Cada URA tem ramal próprio, perguntas próprias e integração com Jira opcional.
 */
import { useEffect, useState } from 'react';
import api from '../api/client';
import type { Ura } from '../api/types';

interface Props {
  onSelect: (uraId: number) => void;
}

export default function UraManagementTab({ onSelect }: Props) {
  const [uras, setUras] = useState<Ura[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editUra, setEditUra] = useState<Partial<Ura>>({});
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    const r = await api.get<Ura[]>('/uras');
    setUras(r.data);
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => {
    setEditUra({ name: '', extension: '', active: true, jiraIntegrationEnabled: true });
    setError(null);
    setShowModal(true);
  };

  const openEdit = (u: Ura) => {
    setEditUra({ ...u });
    setError(null);
    setShowModal(true);
  };

  const save = async () => {
    if (!editUra.name?.trim())      { setError('Informe o nome da URA.');   return; }
    if (!editUra.extension?.trim()) { setError('Informe o ramal da URA.');  return; }
    try {
      if (editUra.id) {
        await api.put(`/uras/${editUra.id}`, editUra);
      } else {
        await api.post('/uras', editUra);
      }
      setShowModal(false);
      load();
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Erro ao salvar a URA. Tente novamente.');
    }
  };

  const remove = async (u: Ura) => {
    if (u.id === 1) { alert('A URA padrão (Service Desk) não pode ser removida.'); return; }
    if (!confirm(`Remover a URA "${u.name}"?`)) return;
    await api.delete(`/uras/${u.id}`);
    load();
  };

  if (loading) {
    return <div className="loading-state"><div className="spinner" />Carregando URAs…</div>;
  }

  return (
    <>
      <div className="toolbar">
        <div className="toolbar-left">
          <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>
            Novas URAs devem usar um ramal entre 2000 e 2999
          </span>
        </div>
        <div className="toolbar-right">
          <button className="btn btn-primary btn-sm" onClick={openCreate}>＋ Nova URA</button>
        </div>
      </div>

      <div className="table-wrapper">
        <table>
          <thead>
            <tr>
              <th>Nome</th>
              <th>Ramal</th>
              <th>Integração Jira</th>
              <th>Status</th>
              <th>Ações</th>
            </tr>
          </thead>
          <tbody>
            {uras.map(u => (
              <tr key={u.id}>
                <td>{u.name}</td>
                <td className="mono">{u.extension}</td>
                <td>
                  <span className={`badge ${u.jiraIntegrationEnabled ? 'badge-success' : 'badge-gray'}`}>
                    {u.jiraIntegrationEnabled ? 'Ativada' : 'Desativada'}
                  </span>
                </td>
                <td><span className={`badge ${u.active ? 'badge-success' : 'badge-gray'}`}>{u.active ? 'Ativa' : 'Inativa'}</span></td>
                <td style={{ display: 'flex', gap: 6 }}>
                  <button className="btn btn-ghost btn-sm" onClick={() => onSelect(u.id)}>Configurar</button>
                  <button className="btn btn-ghost btn-sm" onClick={() => openEdit(u)}>Editar</button>
                  <button className="btn btn-ghost btn-sm" onClick={() => remove(u)} disabled={u.id === 1}>Remover</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showModal && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal">
            <div className="modal-header">
              <h2>🎫 {editUra.id ? 'Editar' : 'Nova'} URA</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              {error && (
                <div style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: 8, padding: '8px 12px', marginBottom: 12, fontSize: '.85rem', color: '#dc2626' }}>
                  {error}
                </div>
              )}
              <div className="form-group">
                <label className="form-label">Nome</label>
                <input type="text" className="form-input" value={editUra.name ?? ''}
                  onChange={e => setEditUra(u => ({ ...u, name: e.target.value }))}
                  placeholder="ex: URA de Vendas" />
              </div>
              <div className="form-group">
                <label className="form-label">Ramal <span style={{ fontWeight: 400, color: 'var(--text-muted)' }}>(2000-2999 para URAs novas)</span></label>
                <input type="text" className="form-input" value={editUra.extension ?? ''}
                  onChange={e => setEditUra(u => ({ ...u, extension: e.target.value }))}
                  placeholder="ex: 2001" disabled={editUra.id === 1} />
              </div>
              <div className="form-group" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" id="jira-integration" checked={editUra.jiraIntegrationEnabled ?? true}
                  onChange={e => setEditUra(u => ({ ...u, jiraIntegrationEnabled: e.target.checked }))} />
                <label htmlFor="jira-integration" className="form-label" style={{ marginBottom: 0 }}>
                  Ativar integração com Jira
                </label>
              </div>
              <div className="form-group" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" id="ura-active" checked={editUra.active ?? true}
                  onChange={e => setEditUra(u => ({ ...u, active: e.target.checked }))} />
                <label htmlFor="ura-active" className="form-label" style={{ marginBottom: 0 }}>
                  URA ativa
                </label>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save}>
                {editUra.id ? 'Salvar Alterações' : 'Criar URA'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
