import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import { useAuthSession } from '../hooks/useAuthSession';
import type { BusinessUnit, Client, Numero0800, Operadora } from '../api/types';
import ImportModal, { triggerDownload } from './shared/ImportModal';

const MAX_REGENERADOS = 5;

/** Estado de um grupo "Regenerado" no formulário — campos de texto sempre string (nunca undefined) para inputs controlados. */
interface RegeneradoForm {
  id?: number;
  ordem: number;
  numeroRegenerado: string;
  vdn: string;
  vetor: string;
  operadoraId: number | null;
}

/** Payload enviado para POST/PUT /numeros-0800 — client/operadora nulos removem o vínculo. */
interface Numero0800Payload {
  operadora: { id: number } | null;
  numero: string;
  client: { id: number } | null;
  observacao: string;
  isActive: boolean;
  regenerados: Array<{ id?: number; ordem: number; numeroRegenerado: string; vdn: string; vetor: string; operadora: { id: number } | null }>;
}

const EMPTY_REGENERADO = (ordem: number): RegeneradoForm => ({
  ordem, numeroRegenerado: '', vdn: '', vetor: '', operadoraId: null,
});

const EMPTY_FORM = {
  operadora: null as { id: number } | null, numero: '', client: null as { id: number } | null,
  observacao: '', isActive: true, regenerados: [] as RegeneradoForm[],
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
function RegeneradoCard({ index, value, operadoraOptions, onChangeText, onChangeOperadora, onRemove }: {
  index: number;
  value: RegeneradoForm;
  operadoraOptions: Operadora[];
  onChangeText: (field: 'numeroRegenerado' | 'vdn' | 'vetor', val: string) => void;
  onChangeOperadora: (operadoraId: number | null) => void;
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
            onChange={e => onChangeText('numeroRegenerado', e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label">VDN</label>
          <input
            type="text"
            className="form-input"
            value={value.vdn}
            onChange={e => onChangeText('vdn', e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label">Vetor</label>
          <input
            type="text"
            className="form-input"
            value={value.vetor}
            onChange={e => onChangeText('vetor', e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label">Operadora</label>
          <select
            className="form-select"
            value={value.operadoraId ?? 0}
            onChange={e => {
              const id = +e.target.value;
              onChangeOperadora(id ? id : null);
            }}
          >
            <option value={0}>Nenhuma</option>
            {operadoraOptions.map(o => <option key={o.id} value={o.id}>{o.nome}</option>)}
          </select>
        </div>
      </div>
    </div>
  );
}

/** true se os 3 campos de texto estiverem vazios e nenhuma operadora selecionada — usado para descartar grupos não preenchidos ao salvar. */
function isRegeneradoEmpty(r: RegeneradoForm): boolean {
  return !r.numeroRegenerado.trim() && !r.vdn.trim() && !r.vetor.trim() && r.operadoraId == null;
}

/** Monta a lista de regenerados a enviar à API: descarta grupos totalmente vazios e renumera `ordem` 1..N. */
function buildRegeneradosPayload(regenerados: RegeneradoForm[]): Numero0800Payload['regenerados'] {
  return regenerados
    .filter(r => !isRegeneradoEmpty(r))
    .map((r, i) => ({
      ...(r.id != null ? { id: r.id } : {}),
      ordem: i + 1,
      numeroRegenerado: r.numeroRegenerado.trim(),
      vdn: r.vdn.trim(),
      vetor: r.vetor.trim(),
      operadora: r.operadoraId != null ? { id: r.operadoraId } : null,
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
      operadoraId: r.operadora?.id ?? null,
    }));
}

export default function Cadastro0800() {
  const { hasWrite: sessionHasWrite } = useAuthSession();
  const hasWrite = sessionHasWrite('telecom.0800');

  const [items, setItems] = useState<Numero0800[]>([]);
  const [loading, setLoading] = useState(true);
  const [buOptions, setBuOptions] = useState<BusinessUnit[]>([]);
  const [clientOptions, setClientOptions] = useState<Client[]>([]);
  const [operadoraOptions, setOperadoraOptions] = useState<Operadora[]>([]);
  const [filterBu, setFilterBu] = useState('');

  const [showModal, setShowModal] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [form, setForm] = useState({ ...EMPTY_FORM });
  const [selectedBuIds, setSelectedBuIds] = useState<number[]>([]);
  const [saving, setSaving] = useState(false);
  // Operadoras inativas já vinculadas a este item (top-level ou em algum
  // regenerado) — precisam aparecer no <select> de edição mesmo fora do
  // filtro active=true, senão o campo aparece em branco apesar do vínculo
  // continuar válido.
  const [operadoraFallbacks, setOperadoraFallbacks] = useState<Operadora[]>([]);
  const [showImportModal, setShowImportModal] = useState(false);

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
      api.get<Operadora[]>('/operadoras?active=true'),
    ]).then(([b, c, o]) => {
      setBuOptions(b.data ?? []);
      setClientOptions(c.data ?? []);
      setOperadoraOptions(o.data ?? []);
    }).catch(err => console.error('Erro ao carregar dados mestres para Números 0800:', err));
  }, []);

  const openCreate = () => {
    setEditId(null);
    setForm({ ...EMPTY_FORM, regenerados: [] });
    setSelectedBuIds([]);
    setOperadoraFallbacks([]);
    setShowModal(true);
  };

  const openEdit = (item: Numero0800) => {
    setEditId(item.id);
    setForm({
      operadora: item.operadora ? { id: item.operadora.id } : null,
      numero: item.numero,
      client: item.client ? { id: item.client.id } : null,
      observacao: item.observacao ?? '',
      isActive: item.isActive,
      regenerados: toRegeneradoForm(item),
    });
    setSelectedBuIds(item.businessUnits?.map(bu => bu.id) ?? []);
    const fallbacks: Operadora[] = [];
    if (item.operadora) fallbacks.push({ id: item.operadora.id, nome: item.operadora.nome ?? `#${item.operadora.id}`, isActive: false });
    item.regenerados.forEach(r => {
      if (r.operadora) fallbacks.push({ id: r.operadora.id, nome: r.operadora.nome ?? `#${r.operadora.id}`, isActive: false });
    });
    setOperadoraFallbacks(fallbacks);
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

  const updateRegeneradoText = (index: number, field: 'numeroRegenerado' | 'vdn' | 'vetor', value: string) => {
    setForm(f => ({
      ...f,
      regenerados: f.regenerados.map((r, i) => i === index ? { ...r, [field]: value } : r),
    }));
  };

  const updateRegeneradoOperadora = (index: number, operadoraId: number | null) => {
    setForm(f => ({
      ...f,
      regenerados: f.regenerados.map((r, i) => i === index ? { ...r, operadoraId } : r),
    }));
  };

  const save = async () => {
    if (!form.operadora || !form.numero.trim()) return;
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
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar.'));
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (item: Numero0800) => {
    try {
      await api.put(`/numeros-0800/${item.id}`, {
        operadora: item.operadora ? { id: item.operadora.id } : null,
        numero: item.numero,
        client: item.client ? { id: item.client.id } : null,
        observacao: item.observacao ?? '',
        isActive: !item.isActive,
        regenerados: buildRegeneradosPayload(toRegeneradoForm(item)),
      });
      await api.put(`/numeros-0800/${item.id}/business-units`, item.businessUnits?.map(bu => bu.id) ?? []);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao alterar status.'));
    }
  };

  const remove = async (item: Numero0800) => {
    if (!confirm(`Remover o número 0800 "${item.numero}"? Esta ação não pode ser desfeita.`)) return;
    try {
      await api.delete(`/numeros-0800/${item.id}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover.'));
    }
  };

  const handleExport = async () => {
    try {
      const res = await api.get('/numeros-0800/export', { responseType: 'blob' });
      triggerDownload(res.data, `numeros-0800_${new Date().toISOString().slice(0, 10)}.xlsx`);
    } catch {
      alert('Erro ao exportar.');
    }
  };

  const filteredItems = filterBu
    ? items.filter(item => item.businessUnits?.some(bu => String(bu.id) === filterBu))
    : items;

  const activeCount = filteredItems.filter(i => i.isActive).length;

  const operadoraSelectOptions = [
    ...operadoraOptions,
    ...operadoraFallbacks.filter(f => !operadoraOptions.some(o => o.id === f.id)),
  ];

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
          <div className="toolbar-right" style={{ gap: 8 }}>
            <button className="btn btn-ghost btn-sm" onClick={handleExport}>📤 Exportar</button>
            {hasWrite && (
              <button className="btn btn-ghost btn-sm" onClick={() => setShowImportModal(true)}>📥 Importar</button>
            )}
            {hasWrite && (
              <button className="btn btn-primary" onClick={openCreate}>＋ Novo 0800</button>
            )}
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
                    <td style={{ fontWeight: 500 }}>{item.operadora?.nome ?? '—'}</td>
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
                  <select
                    className="form-select"
                    value={form.operadora?.id ?? 0}
                    onChange={e => {
                      const id = +e.target.value;
                      setForm(f => ({ ...f, operadora: id ? { id } : null }));
                    }}
                    autoFocus
                  >
                    <option value={0}>Selecione…</option>
                    {operadoraSelectOptions.map(o => <option key={o.id} value={o.id}>{o.nome}</option>)}
                  </select>
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
                    operadoraOptions={operadoraSelectOptions}
                    onChangeText={(field, value) => updateRegeneradoText(i, field, value)}
                    onChangeOperadora={id => updateRegeneradoOperadora(i, id)}
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
                disabled={saving || !form.operadora || !form.numero.trim()}
              >
                {saving ? 'Salvando…' : editId ? 'Salvar Alterações' : 'Criar'}
              </button>
            </div>
          </div>
        </div>
      )}

      {showImportModal && (
        <ImportModal
          title="Importar Números 0800 em Lote"
          importUrl="/numeros-0800/import"
          templateUrl="/numeros-0800/template"
          templateFilename="modelo-numeros-0800.xlsx"
          instructions={[
            'Baixe o arquivo modelo e preencha a aba "Modelo".',
            'Os nomes de Operadora, Cliente e BU devem ser exatamente como cadastrados.',
            'A aba "Valores de Referência" lista os nomes válidos.',
            'Campo "Ativo": Sim ou Não.',
            'Os campos de Regenerado são opcionais — deixe em branco os grupos que não usar.',
          ]}
          onClose={() => setShowImportModal(false)}
          onImported={load}
        />
      )}
    </>
  );
}
