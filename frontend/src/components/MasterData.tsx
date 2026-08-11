import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';

type TabKey = 'bu' | 'client' | 'operation' | 'segment';

/** Referência mínima de BU vinda embutida no JSON de Client/Operation. */
interface BusinessUnitRef {
  id: number;
  name: string;
}

interface GenericEntity {
  id: number;
  name: string;
  isActive: boolean;
  [key: string]: unknown;
}

// Ordem de exibição do menu: BU, Operação, Segmento, Cliente.
const TABS: { key: TabKey; label: string; icon: string; endpoint: string }[] = [
  { key: 'bu',        label: 'BU',        icon: '🏢', endpoint: 'business-units' },
  { key: 'operation', label: 'Operações',       icon: '⚙️',  endpoint: 'operations' },
  { key: 'segment',   label: 'Segmentos',       icon: '📂', endpoint: 'segments' },
  { key: 'client',    label: 'Clientes',       icon: '👤', endpoint: 'clients' },
];

/** Lista de chips clicáveis (checkbox) para seleção múltipla opcional de BU/Cliente. */
function MultiSelectChecklist({ options, selectedIds, onChange, emptyMessage }: {
  options: GenericEntity[];
  selectedIds: number[];
  onChange: (ids: number[]) => void;
  emptyMessage: string;
}) {
  const toggle = (id: number) => {
    onChange(selectedIds.includes(id) ? selectedIds.filter(i => i !== id) : [...selectedIds, id]);
  };

  if (options.length === 0) {
    return <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>{emptyMessage}</p>;
  }

  return (
    <div style={{
      maxHeight: 160, overflowY: 'auto', border: '1px solid var(--border-glass)',
      borderRadius: 6, padding: 8, display: 'flex', flexWrap: 'wrap', gap: 8,
    }}>
      {options.map(opt => (
        <label key={opt.id} className="chip" style={{ cursor: 'pointer', gap: 6 }}>
          <input
            type="checkbox"
            checked={selectedIds.includes(opt.id)}
            onChange={() => toggle(opt.id)}
            style={{ marginRight: 4 }}
          />
          {opt.name}
        </label>
      ))}
    </div>
  );
}

export default function MasterData() {
  const [tab, setTab] = useState<TabKey>('bu');
  const [items, setItems] = useState<GenericEntity[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editItem, setEditItem] = useState<Partial<GenericEntity>>({});
  const [saving, setSaving] = useState(false);

  // Opções para os multi-selects opcionais das abas Cliente/Operação.
  const [buOptions, setBuOptions] = useState<GenericEntity[]>([]);
  const [clientOptions, setClientOptions] = useState<GenericEntity[]>([]);
  const [selectedBuIds, setSelectedBuIds] = useState<number[]>([]);
  const [selectedClientIds, setSelectedClientIds] = useState<number[]>([]);

  const currentTab = TABS.find(t => t.key === tab)!;

  const load = () => {
    setLoading(true);
    api.get<GenericEntity[]>(`/${currentTab.endpoint}`)
      .then(r => setItems(r.data ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, [tab]);

  // Carrega as listas usadas pelos multi-selects das abas Cliente/Operação.
  useEffect(() => {
    if (tab === 'client' || tab === 'operation') {
      api.get<GenericEntity[]>('/business-units')
        .then(r => setBuOptions(r.data ?? []))
        .catch(() => setBuOptions([]));
    }
    if (tab === 'operation') {
      api.get<GenericEntity[]>('/clients')
        .then(r => setClientOptions(r.data ?? []))
        .catch(() => setClientOptions([]));
    }
  }, [tab]);

  const openCreate = () => {
    setEditItem({ name: '', isActive: true });
    setSelectedBuIds([]);
    setSelectedClientIds([]);
    setShowModal(true);
  };

  const openEdit = (item: GenericEntity) => {
    setEditItem({ ...item });
    const buRefs = item.businessUnits as BusinessUnitRef[] | undefined;
    setSelectedBuIds(buRefs?.map(bu => bu.id) ?? []);
    // Client.operations tem @JsonIgnore no backend (evita ciclo de serialização) e não existe
    // endpoint "quais clientes têm a operação X" — por isso o multi-select de Clientes começa
    // vazio ao editar uma Operação (limitação aceita; o usuário reseleciona se quiser alterar).
    setSelectedClientIds([]);
    setShowModal(true);
  };

  const save = async () => {
    if (!editItem.name?.trim()) return;
    setSaving(true);
    try {
      const res = editItem.id
        ? await api.put(`/${currentTab.endpoint}/${editItem.id}`, editItem)
        : await api.post(`/${currentTab.endpoint}`, editItem);
      const savedId = res.data.id;

      if (tab === 'client') {
        await api.put(`/clients/${savedId}/business-units`, selectedBuIds);
      } else if (tab === 'operation') {
        await api.put(`/operations/${savedId}/business-units`, selectedBuIds);
        await api.put(`/operations/${savedId}/clients`, selectedClientIds);
      }

      setShowModal(false);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar.'));
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (item: GenericEntity) => {
    try {
      await api.put(`/${currentTab.endpoint}/${item.id}`, { ...item, isActive: !item.isActive });
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao alterar status.'));
    }
  };

  const remove = async (item: GenericEntity) => {
    if (!confirm(`Remover "${item.name}"? Esta ação não pode ser desfeita.`)) return;
    try {
      await api.delete(`/${currentTab.endpoint}/${item.id}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover.'));
    }
  };

  const activeCount = items.filter(i => i.isActive).length;

  return (
    <>
      <div className="page-header">
        <h1>👤 Clientes</h1>
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

              {tab === 'client' && (
                <div className="form-group">
                  <label className="form-label">Unidades de Negócio (opcional)</label>
                  <MultiSelectChecklist
                    options={buOptions}
                    selectedIds={selectedBuIds}
                    onChange={setSelectedBuIds}
                    emptyMessage="Nenhuma BU cadastrada."
                  />
                </div>
              )}

              {tab === 'operation' && (
                <>
                  <div className="form-group">
                    <label className="form-label">Unidades de Negócio (opcional)</label>
                    <MultiSelectChecklist
                      options={buOptions}
                      selectedIds={selectedBuIds}
                      onChange={setSelectedBuIds}
                      emptyMessage="Nenhuma BU cadastrada."
                    />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Clientes vinculados (opcional)</label>
                    <MultiSelectChecklist
                      options={clientOptions}
                      selectedIds={selectedClientIds}
                      onChange={setSelectedClientIds}
                      emptyMessage="Nenhum cliente cadastrado."
                    />
                    {editItem.id != null && (
                      <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                        Os clientes já vinculados não são pré-selecionados automaticamente ao editar
                        (limitação conhecida). Selecione novamente se quiser alterar o vínculo.
                      </p>
                    )}
                  </div>
                </>
              )}
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
