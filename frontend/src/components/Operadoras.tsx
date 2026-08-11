import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import { useAuthSession } from '../hooks/useAuthSession';
import type { Operadora } from '../api/types';

interface OperadoraPayload {
  nome: string;
  isActive: boolean;
}

const EMPTY_FORM: OperadoraPayload = { nome: '', isActive: true };

export default function Operadoras() {
  const { hasWrite: sessionHasWrite } = useAuthSession();
  const hasWrite = sessionHasWrite('telecom.operadoras');

  const [items, setItems] = useState<Operadora[]>([]);
  const [loading, setLoading] = useState(true);

  const [showModal, setShowModal] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [form, setForm] = useState<OperadoraPayload>({ ...EMPTY_FORM });
  const [saving, setSaving] = useState(false);

  const load = () => {
    setLoading(true);
    api.get<Operadora[]>('/operadoras')
      .then(r => setItems(r.data ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => {
    setEditId(null);
    setForm({ ...EMPTY_FORM });
    setShowModal(true);
  };

  const openEdit = (item: Operadora) => {
    setEditId(item.id);
    setForm({ nome: item.nome, isActive: item.isActive });
    setShowModal(true);
  };

  const save = async () => {
    if (!form.nome.trim()) return;
    setSaving(true);
    try {
      if (editId) {
        await api.put(`/operadoras/${editId}`, form);
      } else {
        await api.post('/operadoras', form);
      }
      setShowModal(false);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar.'));
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (item: Operadora) => {
    try {
      await api.put(`/operadoras/${item.id}`, { nome: item.nome, isActive: !item.isActive });
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao alterar status.'));
    }
  };

  const remove = async (item: Operadora) => {
    if (!confirm(`Remover a operadora "${item.nome}"? Esta ação não pode ser desfeita.`)) return;
    try {
      await api.delete(`/operadoras/${item.id}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover — verifique se ela não está em uso por algum número 0800 ou linha.'));
    }
  };

  const activeCount = items.filter(i => i.isActive).length;

  return (
    <>
      <div className="page-header">
        <h1>🏢 Operadoras</h1>
        <p>Cadastro de operadoras — referenciadas pelas telas de Números 0800 e Linhas</p>
      </div>
      <div className="page-body">

        {/* Toolbar */}
        <div className="toolbar" style={{ flexWrap: 'wrap', gap: 8 }}>
          <div className="toolbar-left">
            <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
              {activeCount} ativa{activeCount !== 1 ? 's' : ''} · {items.length} total
            </span>
          </div>
          {hasWrite && (
            <div className="toolbar-right">
              <button className="btn btn-primary" onClick={openCreate}>＋ Nova Operadora</button>
            </div>
          )}
        </div>

        {/* Table */}
        {loading ? (
          <div className="loading-state"><div className="spinner" />Carregando…</div>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th style={{ width: 60 }}>#</th>
                  <th>Nome</th>
                  <th style={{ width: 100 }}>Status</th>
                  {hasWrite && <th style={{ width: 140 }}>Ações</th>}
                </tr>
              </thead>
              <tbody>
                {items.length === 0 ? (
                  <tr>
                    <td colSpan={hasWrite ? 4 : 3} className="table-empty">
                      Nenhuma operadora cadastrada.<br />
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                        Clique em "＋ Nova Operadora" para adicionar.
                      </span>
                    </td>
                  </tr>
                ) : items.map(item => (
                  <tr key={item.id}>
                    <td className="td-muted">{item.id}</td>
                    <td style={{ fontWeight: 500 }}>{item.nome}</td>
                    <td>
                      <span className={`badge ${item.isActive ? 'badge-success' : 'badge-gray'}`}>
                        {item.isActive ? 'Ativo' : 'Inativo'}
                      </span>
                    </td>
                    {hasWrite && (
                      <td>
                        <div className="flex gap-1">
                          <button
                            className="btn btn-ghost btn-sm btn-icon"
                            onClick={() => openEdit(item)}
                            title="Editar"
                          >✏️</button>
                          <button
                            className="btn btn-ghost btn-sm btn-icon"
                            onClick={() => toggleActive(item)}
                            title={item.isActive ? 'Desativar' : 'Ativar'}
                          >{item.isActive ? '⏸' : '▶️'}</button>
                          <button
                            className="btn btn-danger btn-sm btn-icon"
                            onClick={() => remove(item)}
                            title="Remover"
                          >🗑️</button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Modal */}
      {showModal && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal modal-sm">
            <div className="modal-header">
              <h2>🏢 {editId ? 'Editar' : 'Nova'} Operadora</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Nome *</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="Nome da operadora"
                  value={form.nome}
                  onChange={e => setForm(f => ({ ...f, nome: e.target.value }))}
                  autoFocus
                />
              </div>
              <div className="form-group">
                <label className="form-label">Status</label>
                <select
                  className="form-select"
                  value={form.isActive ? 'true' : 'false'}
                  onChange={e => setForm(f => ({ ...f, isActive: e.target.value === 'true' }))}
                >
                  <option value="true">Ativo</option>
                  <option value="false">Inativo</option>
                </select>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.nome.trim()}>
                {saving ? 'Salvando…' : editId ? 'Salvar Alterações' : 'Criar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
