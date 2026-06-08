import { useEffect, useState } from 'react';
import api from '../api/client';

type TabKey = 'bu' | 'client' | 'operation' | 'segment';

interface GenericEntity {
  id: number;
  name: string;
  isActive: boolean;
  [key: string]: unknown;
}

const TABS: { key: TabKey; label: string; icon: string; endpoint: string }[] = [
  { key: 'bu',        label: 'BU',        icon: '🏢', endpoint: 'business-units' },
  { key: 'client',    label: 'Clientes',       icon: '👤', endpoint: 'clients' },
  { key: 'operation', label: 'Operações',       icon: '⚙️',  endpoint: 'operations' },
  { key: 'segment',   label: 'Segmentos',       icon: '📂', endpoint: 'segments' },
];

export default function MasterData() {
  const [tab, setTab] = useState<TabKey>('bu');
  const [items, setItems] = useState<GenericEntity[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editItem, setEditItem] = useState<Partial<GenericEntity>>({});
  const [saving, setSaving] = useState(false);

  const currentTab = TABS.find(t => t.key === tab)!;

  const load = () => {
    setLoading(true);
    api.get<GenericEntity[]>(`/${currentTab.endpoint}`)
      .then(r => setItems(r.data ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, [tab]);

  const openCreate = () => {
    setEditItem({ name: '', isActive: true });
    setShowModal(true);
  };

  const openEdit = (item: GenericEntity) => {
    setEditItem({ ...item });
    setShowModal(true);
  };

  const save = async () => {
    if (!editItem.name?.trim()) return;
    setSaving(true);
    try {
      if (editItem.id) {
        await api.put(`/${currentTab.endpoint}/${editItem.id}`, editItem);
      } else {
        await api.post(`/${currentTab.endpoint}`, editItem);
      }
      setShowModal(false);
      load();
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (item: GenericEntity) => {
    await api.put(`/${currentTab.endpoint}/${item.id}`, { ...item, isActive: !item.isActive });
    load();
  };

  const remove = async (item: GenericEntity) => {
    if (!confirm(`Remover "${item.name}"? Esta ação não pode ser desfeita.`)) return;
    await api.delete(`/${currentTab.endpoint}/${item.id}`);
    load();
  };

  const activeCount = items.filter(i => i.isActive).length;

  return (
    <>
      <div className="page-header">
        <h1>⚙️ Dados Mestres</h1>
        <p>Gerenciar Business Units, Clientes, Operações e Segmentos utilizados no Módulo 2</p>
      </div>
      <div className="page-body">

        {/* Tabs */}
        <div className="flex gap-1" style={{ marginBottom: 20, flexWrap: 'wrap' }}>
          {TABS.map(t => (
            <button
              key={t.key}
              className={`btn ${tab === t.key ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => setTab(t.key)}
            >
              {t.icon} {t.label}
            </button>
          ))}
        </div>

        {/* Toolbar */}
        <div className="toolbar">
          <div className="toolbar-left">
            <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
              {activeCount} {currentTab.label.toLowerCase()} ativ{activeCount !== 1 ? 'os' : 'o'} · {items.length} total
            </span>
          </div>
          <div className="toolbar-right">
            <button className="btn btn-primary" onClick={openCreate}>
              ＋ {currentTab.label === 'BU' ? 'Nova BU' : currentTab.label === 'Clientes' ? 'Novo Cliente' : currentTab.label === 'Operações' ? 'Nova Operação' : 'Novo Segmento'}
            </button>
          </div>
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
                  <th style={{ width: 120 }}>Status</th>
                  <th style={{ width: 160 }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {items.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="table-empty">
                      Nenhum registro cadastrado.<br />
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                        Clique em "＋ Novo" para adicionar.
                      </span>
                    </td>
                  </tr>
                ) : items.map(item => (
                  <tr key={item.id}>
                    <td className="td-muted">{item.id}</td>
                    <td style={{ fontWeight: 500 }}>{item.name}</td>
                    <td>
                      <span className={`badge ${item.isActive ? 'badge-success' : 'badge-gray'}`}>
                        {item.isActive ? 'Ativo' : 'Inativo'}
                      </span>
                    </td>
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
              <h2>{currentTab.icon} {editItem.id ? 'Editar' : 'Novo'} {currentTab.label.replace(/s$/, '')}</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Nome *</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder={`Nome da ${currentTab.label.replace(/s$/, '').toLowerCase()}`}
                  value={editItem.name ?? ''}
                  onChange={e => setEditItem(i => ({ ...i, name: e.target.value }))}
                  autoFocus
                />
              </div>
              <div className="form-group">
                <label className="form-label">Status</label>
                <select
                  className="form-select"
                  value={editItem.isActive ? 'true' : 'false'}
                  onChange={e => setEditItem(i => ({ ...i, isActive: e.target.value === 'true' }))}
                >
                  <option value="true">Ativo</option>
                  <option value="false">Inativo</option>
                </select>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !editItem.name?.trim()}>
                {saving ? 'Salvando…' : editItem.id ? 'Salvar Alterações' : 'Criar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
