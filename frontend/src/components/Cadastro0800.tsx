import { useEffect, useState } from 'react';
import api, { canWrite, getPermissionsFromToken, getRoleFromToken } from '../api/client';
import type { BusinessUnit, Client, Numero0800 } from '../api/types';

const MAX_REGENERADOS = 5;

/** Estado de um grupo "Regenerado" no formulário — campos sempre string (nunca undefined) para inputs controlados. */
interface RegeneradoForm {
  id?: number;
  ordem: number;
  numeroRegenerado: string;
  vdn: string;
  vetor: string;
  operadora: string;
}

/** Payload enviado para POST/PUT /numeros-0800 — client nulo remove o vínculo. */
interface Numero0800Payload {
  operadora: string;
  numero: string;
  client: { id: number } | null;
  observacao: string;
  isActive: boolean;
  regenerados: RegeneradoForm[];
}

const EMPTY_REGENERADO = (ordem: number): RegeneradoForm => ({
  ordem, numeroRegenerado: '', vdn: '', vetor: '', operadora: '',
});

const EMPTY_FORM: Numero0800Payload = {
  operadora: '', numero: '', client: null, observacao: '', isActive: true, regenerados: [],
};

/** Lista de chips clicáveis (checkbox) para seleção múltipla opcional de BU — mesmo padrão de MasterData.tsx/Linhas.tsx. */
function MultiSelectChecklist({ options, selectedIds, onChange, emptyMessage }: {
  options: BusinessUnit[];
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

/** Um grupo "Regenerado N" — card com os 4 campos e botão de remover. */
function RegeneradoCard({ index, value, onChange, onRemove }: {
  index: number;
  value: RegeneradoForm;
  onChange: (field: keyof RegeneradoForm, val: string) => void;
  onRemove: () => void;
}) {
  return (
    <div className="form-section" style={{
      border: '1px solid var(--border-glass)', borderRadius: 10, padding: 14,
      marginBottom: 12, background: 'rgba(99,102,241,0.03)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <span style={{ fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
          Regenerado {index + 1}
        </span>
        <button className="btn btn-danger btn-sm" onClick={onRemove}>Remover</button>
      </div>
      <div className="form-grid">
        <div className="form-group">
          <label className="form-label">Número Regenerado</label>
          <input
            type="text"
            className="form-input"
            value={value.numeroRegenerado}
            onChange={e => onChange('numeroRegenerado', e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label">VDN</label>
          <input
            type="text"
            className="form-input"
            value={value.vdn}
            onChange={e => onChange('vdn', e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label">Vetor</label>
          <input
            type="text"
            className="form-input"
            value={value.vetor}
            onChange={e => onChange('vetor', e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label">Operadora</label>
          <input
            type="text"
            className="form-input"
            value={value.operadora}
            onChange={e => onChange('operadora', e.target.value)}
          />
        </div>
      </div>
    </div>
  );
}

/** true se os 4 campos do grupo estiverem vazios — usado para descartar grupos não preenchidos ao salvar. */
function isRegeneradoEmpty(r: RegeneradoForm): boolean {
  return !r.numeroRegenerado.trim() && !r.vdn.trim() && !r.vetor.trim() && !r.operadora.trim();
}

/** Monta a lista de regenerados a enviar à API: descarta grupos totalmente vazios e renumera `ordem` 1..N. */
function buildRegeneradosPayload(regenerados: RegeneradoForm[]): RegeneradoForm[] {
  return regenerados
    .filter(r => !isRegeneradoEmpty(r))
    .map((r, i) => ({
      ...(r.id != null ? { id: r.id } : {}),
      ordem: i + 1,
      numeroRegenerado: r.numeroRegenerado.trim(),
      vdn: r.vdn.trim(),
      vetor: r.vetor.trim(),
      operadora: r.operadora.trim(),
    }));
}

/** Converte os regenerados vindos da API (podem ter campos nulos) para o estado do formulário. */
function toRegeneradoForm(item: Numero0800): RegeneradoForm[] {
  return [...item.regenerados]
    .sort((a, b) => a.ordem - b.ordem)
    .map(r => ({
      id: r.id,
      ordem: r.ordem,
      numeroRegenerado: r.numeroRegenerado ?? '',
      vdn: r.vdn ?? '',
      vetor: r.vetor ?? '',
      operadora: r.operadora ?? '',
    }));
}

export default function Cadastro0800() {
  const token = localStorage.getItem('asteriskia_token');
  const role = getRoleFromToken(token);
  const perms = getPermissionsFromToken(token);
  const hasWrite = canWrite(role, perms, 'telecom.0800');

  const [items, setItems] = useState<Numero0800[]>([]);
  const [loading, setLoading] = useState(true);
  const [buOptions, setBuOptions] = useState<BusinessUnit[]>([]);
  const [clientOptions, setClientOptions] = useState<Client[]>([]);
  const [filterBu, setFilterBu] = useState('');

  const [showModal, setShowModal] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [form, setForm] = useState<Numero0800Payload>({ ...EMPTY_FORM });
  const [selectedBuIds, setSelectedBuIds] = useState<number[]>([]);
  const [saving, setSaving] = useState(false);

  const load = () => {
    setLoading(true);
    api.get<Numero0800[]>('/numeros-0800')
      .then(r => setItems(r.data ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    Promise.all([
      api.get<BusinessUnit[]>('/business-units?active=true'),
      api.get<Client[]>('/clients?active=true'),
    ]).then(([b, c]) => {
      setBuOptions(b.data ?? []);
      setClientOptions(c.data ?? []);
    }).catch(err => console.error('Erro ao carregar dados mestres para Números 0800:', err));
  }, []);

  const openCreate = () => {
    setEditId(null);
    setForm({ ...EMPTY_FORM, regenerados: [] });
    setSelectedBuIds([]);
    setShowModal(true);
  };

  const openEdit = (item: Numero0800) => {
    setEditId(item.id);
    setForm({
      operadora: item.operadora,
      numero: item.numero,
      client: item.client ? { id: item.client.id } : null,
      observacao: item.observacao ?? '',
      isActive: item.isActive,
      regenerados: toRegeneradoForm(item),
    });
    setSelectedBuIds(item.businessUnits?.map(bu => bu.id) ?? []);
    setShowModal(true);
  };

  const addRegenerado = () => {
    setForm(f => f.regenerados.length >= MAX_REGENERADOS
      ? f
      : { ...f, regenerados: [...f.regenerados, EMPTY_REGENERADO(f.regenerados.length + 1)] });
  };

  const removeRegenerado = (index: number) => {
    setForm(f => ({
      ...f,
      regenerados: f.regenerados.filter((_, i) => i !== index).map((r, i) => ({ ...r, ordem: i + 1 })),
    }));
  };

  const updateRegeneradoField = (index: number, field: keyof RegeneradoForm, value: string) => {
    setForm(f => ({
      ...f,
      regenerados: f.regenerados.map((r, i) => i === index ? { ...r, [field]: value } : r),
    }));
  };

  const save = async () => {
    if (!form.operadora.trim() || !form.numero.trim()) return;
    setSaving(true);
    try {
      const payload: Numero0800Payload = {
        operadora: form.operadora,
        numero: form.numero,
        client: form.client,
        observacao: form.observacao,
        isActive: form.isActive,
        regenerados: buildRegeneradosPayload(form.regenerados),
      };
      const res = editId
        ? await api.put(`/numeros-0800/${editId}`, payload)
        : await api.post('/numeros-0800', payload);
      const savedId = res.data.id;
      await api.put(`/numeros-0800/${savedId}/business-units`, selectedBuIds);

      setShowModal(false);
      load();
    } catch (err: any) {
      alert(err?.response?.data?.error ?? err?.response?.data?.message ?? 'Erro ao salvar.');
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (item: Numero0800) => {
    try {
      await api.put(`/numeros-0800/${item.id}`, {
        operadora: item.operadora,
        numero: item.numero,
        client: item.client ? { id: item.client.id } : null,
        observacao: item.observacao ?? '',
        isActive: !item.isActive,
        regenerados: toRegeneradoForm(item),
      });
      await api.put(`/numeros-0800/${item.id}/business-units`, item.businessUnits?.map(bu => bu.id) ?? []);
      load();
    } catch (err: any) {
      alert(err?.response?.data?.error ?? err?.response?.data?.message ?? 'Erro ao alterar status.');
    }
  };

  const remove = async (item: Numero0800) => {
    if (!confirm(`Remover o número 0800 "${item.numero}"? Esta ação não pode ser desfeita.`)) return;
    try {
      await api.delete(`/numeros-0800/${item.id}`);
      load();
    } catch (err: any) {
      alert(err?.response?.data?.error ?? err?.response?.data?.message ?? 'Erro ao remover.');
    }
  };

  const filteredItems = filterBu
    ? items.filter(item => item.businessUnits?.some(bu => String(bu.id) === filterBu))
    : items;

  const activeCount = filteredItems.filter(i => i.isActive).length;

  return (
    <>
      <div className="page-header">
        <h1>📞 Números 0800</h1>
        <p>Cadastro de números 0800 — vínculo opcional a Cliente, Unidades de Negócio e grupos de regeneração</p>
      </div>
      <div className="page-body">

        {/* Toolbar */}
        <div className="toolbar" style={{ flexWrap: 'wrap', gap: 8 }}>
          <div className="toolbar-left" style={{ flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
            <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
              {activeCount} ativo{activeCount !== 1 ? 's' : ''} · {filteredItems.length} total
            </span>
            <select
              className="form-select"
              style={{ width: 180 }}
              value={filterBu}
              onChange={e => setFilterBu(e.target.value)}
            >
              <option value="">Todas as BUs</option>
              {buOptions.map(bu => <option key={bu.id} value={String(bu.id)}>{bu.name}</option>)}
            </select>
          </div>
          {hasWrite && (
            <div className="toolbar-right">
              <button className="btn btn-primary" onClick={openCreate}>＋ Novo 0800</button>
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
                  <th>Operadora</th>
                  <th>Número</th>
                  <th>Cliente</th>
                  <th>Observação</th>
                  <th style={{ width: 130 }}>Regenerados</th>
                  <th style={{ width: 100 }}>Status</th>
                  {hasWrite && <th style={{ width: 140 }}>Ações</th>}
                </tr>
              </thead>
              <tbody>
                {filteredItems.length === 0 ? (
                  <tr>
                    <td colSpan={hasWrite ? 8 : 7} className="table-empty">
                      Nenhum número 0800 cadastrado.<br />
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                        Clique em "＋ Novo 0800" para adicionar.
                      </span>
                    </td>
                  </tr>
                ) : filteredItems.map(item => (
                  <tr key={item.id}>
                    <td className="td-muted">{item.id}</td>
                    <td style={{ fontWeight: 500 }}>{item.operadora}</td>
                    <td className="mono">{item.numero}</td>
                    <td>{item.client?.name ?? '—'}</td>
                    <td className="td-muted">{item.observacao || '—'}</td>
                    <td>
                      <span className={`badge ${item.regenerados.length > 0 ? 'badge-info' : 'badge-gray'}`}>
                        {item.regenerados.length}
                      </span>
                    </td>
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
          <div className="modal modal-lg">
            <div className="modal-header">
              <h2>📞 {editId ? 'Editar' : 'Novo'} Número 0800</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Operadora *</label>
                  <input
                    type="text"
                    className="form-input"
                    placeholder="Nome da operadora"
                    value={form.operadora}
                    onChange={e => setForm(f => ({ ...f, operadora: e.target.value }))}
                    autoFocus
                  />
                </div>
                <div className="form-group">
                  <label className="form-label">Número *</label>
                  <input
                    type="text"
                    className="form-input"
                    placeholder="0800 000 0000"
                    value={form.numero}
                    onChange={e => setForm(f => ({ ...f, numero: e.target.value }))}
                  />
                </div>
              </div>
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Cliente</label>
                  <select
                    className="form-select"
                    value={form.client?.id ?? 0}
                    onChange={e => {
                      const id = +e.target.value;
                      setForm(f => ({ ...f, client: id ? { id } : null }));
                    }}
                  >
                    <option value={0}>Nenhum</option>
                    {clientOptions.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
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
              <div className="form-group">
                <label className="form-label">Observação</label>
                <input
                  type="text"
                  className="form-input"
                  value={form.observacao}
                  onChange={e => setForm(f => ({ ...f, observacao: e.target.value }))}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Unidades de Negócio (opcional)</label>
                <MultiSelectChecklist
                  options={buOptions}
                  selectedIds={selectedBuIds}
                  onChange={setSelectedBuIds}
                  emptyMessage="Nenhuma BU cadastrada."
                />
              </div>

              {/* Regenerados */}
              <div className="form-group" style={{ marginTop: 8 }}>
                <label className="form-label">
                  Regenerados ({form.regenerados.length}/{MAX_REGENERADOS})
                </label>
                {form.regenerados.map((r, i) => (
                  <RegeneradoCard
                    key={i}
                    index={i}
                    value={r}
                    onChange={(field, value) => updateRegeneradoField(i, field, value)}
                    onRemove={() => removeRegenerado(i)}
                  />
                ))}
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={addRegenerado}
                  disabled={form.regenerados.length >= MAX_REGENERADOS}
                  title={form.regenerados.length >= MAX_REGENERADOS ? 'Máximo de 5 regenerados atingido' : undefined}
                >
                  ＋ Adicionar Regenerado
                </button>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button
                className="btn btn-primary"
                onClick={save}
                disabled={saving || !form.operadora.trim() || !form.numero.trim()}
              >
                {saving ? 'Salvando…' : editId ? 'Salvar Alterações' : 'Criar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
