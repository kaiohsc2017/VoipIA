/**
 * UraManagementTab.tsx — CRUD de URAs (Módulo 1).
 * Cada URA tem ramal próprio, perguntas próprias e integração com Jira opcional.
 */
import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { Ura } from '../api/types';
import FluxoURATab from './FluxoURATab';

export default function UraManagementTab() {
  const [uras, setUras] = useState<Ura[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [editUra, setEditUra] = useState<Partial<Ura>>({});
  const [error, setError] = useState<string | null>(null);
  const [configuringUra, setConfiguringUra] = useState<Ura | null>(null);

  const load = async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const r = await api.get<Ura[]>('/uras');
      setUras(r.data);
    } catch (err) {
      setLoadError(getErrorMessage(err, 'Erro ao carregar as URAs. Tente novamente.'));
    } finally {
      setLoading(false);
    }
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
    } catch (err) {
      setError(getErrorMessage(err, 'Erro ao salvar a URA. Tente novamente.'));
    }
  };

  const remove = async (u: Ura) => {
    if (u.id === 1) { alert('A URA padrão (Service Desk) não pode ser removida.'); return; }
    if (!confirm(`Remover a URA "${u.name}"?`)) return;
    try {
      await api.delete(`/uras/${u.id}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover a URA. Tente novamente.'));
    }
  };

  if (loading) {
    return <div className="loading-state"><div className="spinner" />Carregando URAs…</div>;
  }

  if (loadError) {
    return (
      <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
        <p style={{ marginBottom: 12 }}>{loadError}</p>
        <button className="btn btn-primary btn-sm" onClick={load}>Tentar novamente</button>
      </div>
    );
  }

  if (configuringUra) {
    return (
      <>
        <div className="toolbar">
          <div className="toolbar-left">
            <button className="btn btn-ghost btn-sm" onClick={() => setConfiguringUra(null)}>← Voltar para lista de URAs</button>
            <span style={{ fontSize: '.9rem', fontWeight: 600, marginLeft: 12 }}>
              Configurando: {configuringUra.name} <span className="mono" style={{ fontWeight: 400, color: 'var(--text-muted)' }}>(ramal {configuringUra.extension})</span>
            </span>
          </div>
        </div>
        <FluxoURATab uraId={configuringUra.id} />
      </>
    );
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
                  <button className="btn btn-ghost btn-sm" onClick={() => setConfiguringUra(u)}>Configurar</button>
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
                <div style={{ background: 'rgba(255,107,107,0.1)', border: '1px solid rgba(255,107,107,0.3)', borderRadius: 8, padding: '8px 12px', marginBottom: 12, fontSize: '.85rem', color: '#b3342f' }}>
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
