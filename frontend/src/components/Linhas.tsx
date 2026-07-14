import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import { useAuthSession } from '../hooks/useAuthSession';
import type { BusinessUnit, Linha, Operadora, Operation } from '../api/types';
import ImportModal, { triggerDownload } from './shared/ImportModal';

/** Payload enviado para POST/PUT /linhas — operation/operadora nulos removem o vínculo. */
interface LinhaPayload {
  operadora: { id: number } | null;
  operation: { id: number } | null;
  chave: string;
  ipOperadora: string;
  ipAutoglass: string;
  observacao: string;
  isActive: boolean;
}

const EMPTY_FORM: LinhaPayload = {
  operadora: null, operation: null, chave: '', ipOperadora: '', ipAutoglass: '',
  observacao: '', isActive: true,
};

/** Lista de chips clicáveis (checkbox) para seleção múltipla opcional de BU — mesmo padrão de MasterData.tsx. */
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

export default function Linhas() {
  const { hasWrite: sessionHasWrite } = useAuthSession();
  const hasWrite = sessionHasWrite('telecom.linhas');

  const [items, setItems] = useState<Linha[]>([]);
  const [loading, setLoading] = useState(true);
  const [buOptions, setBuOptions] = useState<BusinessUnit[]>([]);
  const [operationOptions, setOperationOptions] = useState<Operation[]>([]);
  const [operadoraOptions, setOperadoraOptions] = useState<Operadora[]>([]);
  const [filterBu, setFilterBu] = useState('');

  const [showModal, setShowModal] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [form, setForm] = useState<LinhaPayload>({ ...EMPTY_FORM });
  const [selectedBuIds, setSelectedBuIds] = useState<number[]>([]);
  const [saving, setSaving] = useState(false);
  // Operadora inativa já vinculada a este item — precisa aparecer no
  // <select> de edição mesmo fora do filtro active=true, senão o campo
  // aparece em branco apesar do vínculo continuar válido.
  const [operadoraFallback, setOperadoraFallback] = useState<Operadora | null>(null);
  const [showImportModal, setShowImportModal] = useState(false);

  const load = () => {
    setLoading(true);
    api.get<Linha[]>('/linhas')
      .then(r => setItems(r.data ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    Promise.all([
      api.get<BusinessUnit[]>('/business-units?active=true'),
      api.get<Operation[]>('/operations?active=true'),
      api.get<Operadora[]>('/operadoras?active=true'),
    ]).then(([b, o, op]) => {
      setBuOptions(b.data ?? []);
      setOperationOptions(o.data ?? []);
      setOperadoraOptions(op.data ?? []);
    }).catch(err => console.error('Erro ao carregar dados mestres para Linhas:', err));
  }, []);

  const openCreate = () => {
    setEditId(null);
    setForm({ ...EMPTY_FORM });
    setSelectedBuIds([]);
    setOperadoraFallback(null);
    setShowModal(true);
  };

  const openEdit = (item: Linha) => {
    setEditId(item.id);
    setOperadoraFallback(item.operadora ? { id: item.operadora.id, nome: item.operadora.nome ?? `#${item.operadora.id}`, isActive: false } : null);
    setForm({
      operadora: item.operadora ? { id: item.operadora.id } : null,
      operation: item.operation ? { id: item.operation.id } : null,
      chave: item.chave ?? '',
      ipOperadora: item.ipOperadora ?? '',
      ipAutoglass: item.ipAutoglass ?? '',
      observacao: item.observacao ?? '',
      isActive: item.isActive,
    });
    setSelectedBuIds(item.businessUnits?.map(bu => bu.id) ?? []);
    setShowModal(true);
  };

  const save = async () => {
    if (!form.operadora) return;
    setSaving(true);
    try {
      const res = editId
        ? await api.put(`/linhas/${editId}`, form)
        : await api.post('/linhas', form);
      const savedId = res.data.id;
      await api.put(`/linhas/${savedId}/business-units`, selectedBuIds);

      setShowModal(false);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar.'));
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (item: Linha) => {
    try {
      await api.put(`/linhas/${item.id}`, {
        operadora: item.operadora ? { id: item.operadora.id } : null,
        operation: item.operation ? { id: item.operation.id } : null,
        chave: item.chave ?? '',
        ipOperadora: item.ipOperadora ?? '',
        ipAutoglass: item.ipAutoglass ?? '',
        observacao: item.observacao ?? '',
        isActive: !item.isActive,
      });
      await api.put(`/linhas/${item.id}/business-units`, item.businessUnits?.map(bu => bu.id) ?? []);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao alterar status.'));
    }
  };

  const remove = async (item: Linha) => {
    if (!confirm(`Remover a linha "${item.operadora?.nome}"? Esta ação não pode ser desfeita.`)) return;
    try {
      await api.delete(`/linhas/${item.id}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover.'));
    }
  };

  const handleExport = async () => {
    try {
      const res = await api.get('/linhas/export', { responseType: 'blob' });
      triggerDownload(res.data, `linhas_${new Date().toISOString().slice(0, 10)}.xlsx`);
    } catch {
      alert('Erro ao exportar.');
    }
  };

  const filteredItems = filterBu
    ? items.filter(item => item.businessUnits?.some(bu => String(bu.id) === filterBu))
    : items;

  const activeCount = filteredItems.filter(i => i.isActive).length;

  const operadoraSelectOptions = operadoraFallback && !operadoraOptions.some(o => o.id === operadoraFallback.id)
    ? [...operadoraOptions, operadoraFallback]
    : operadoraOptions;

  return (
    <>
      <div className="page-header">
        <h1>☎️ Linhas</h1>
        <p>Cadastro de linhas de operadora — vínculo opcional a Operação e Unidades de Negócio</p>
      </div>
      <div className="page-body">

        {/* Toolbar */}
        <div className="toolbar" style={{ flexWrap: 'wrap', gap: 8 }}>
          <div className="toolbar-left" style={{ flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
            <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
              {activeCount} ativa{activeCount !== 1 ? 's' : ''} · {filteredItems.length} total
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
              <button className="btn btn-primary" onClick={openCreate}>＋ Nova Linha</button>
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
                  <th>Operação</th>
                  <th>Chave</th>
                  <th>IP Operadora</th>
                  <th>IP Autoglass</th>
                  <th>Observação</th>
                  <th style={{ width: 100 }}>Status</th>
                  {hasWrite && <th style={{ width: 140 }}>Ações</th>}
                </tr>
              </thead>
              <tbody>
                {filteredItems.length === 0 ? (
                  <tr>
                    <td colSpan={hasWrite ? 9 : 8} className="table-empty">
                      Nenhuma linha cadastrada.<br />
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                        Clique em "＋ Nova Linha" para adicionar.
                      </span>
                    </td>
                  </tr>
                ) : filteredItems.map(item => (
                  <tr key={item.id}>
                    <td className="td-muted">{item.id}</td>
                    <td style={{ fontWeight: 500 }}>{item.operadora?.nome ?? '—'}</td>
                    <td>{item.operation?.name ?? '—'}</td>
                    <td className="td-muted">{item.chave || '—'}</td>
                    <td className="mono td-muted">{item.ipOperadora || '—'}</td>
                    <td className="mono td-muted">{item.ipAutoglass || '—'}</td>
                    <td className="td-muted">{item.observacao || '—'}</td>
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
              <h2>☎️ {editId ? 'Editar' : 'Nova'} Linha</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
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
                <label className="form-label">Operação</label>
                <select
                  className="form-select"
                  value={form.operation?.id ?? 0}
                  onChange={e => {
                    const id = +e.target.value;
                    setForm(f => ({ ...f, operation: id ? { id } : null }));
                  }}
                >
                  <option value={0}>Nenhuma</option>
                  {operationOptions.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label className="form-label">Chave</label>
                <input
                  type="text"
                  className="form-input"
                  value={form.chave}
                  onChange={e => setForm(f => ({ ...f, chave: e.target.value }))}
                />
              </div>
              <div className="form-group">
                <label className="form-label">IP Operadora</label>
                <input
                  type="text"
                  className="form-input"
                  value={form.ipOperadora}
                  onChange={e => setForm(f => ({ ...f, ipOperadora: e.target.value }))}
                />
              </div>
              <div className="form-group">
                <label className="form-label">IP Autoglass</label>
                <input
                  type="text"
                  className="form-input"
                  value={form.ipAutoglass}
                  onChange={e => setForm(f => ({ ...f, ipAutoglass: e.target.value }))}
                />
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
              <div className="form-group">
                <label className="form-label">Unidades de Negócio (opcional)</label>
                <MultiSelectChecklist
                  options={buOptions}
                  selectedIds={selectedBuIds}
                  onChange={setSelectedBuIds}
                  emptyMessage="Nenhuma BU cadastrada."
                />
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.operadora}>
                {saving ? 'Salvando…' : editId ? 'Salvar Alterações' : 'Criar'}
              </button>
            </div>
          </div>
        </div>
      )}

      {showImportModal && (
        <ImportModal
          title="Importar Linhas em Lote"
          importUrl="/linhas/import"
          templateUrl="/linhas/template"
          templateFilename="modelo-linhas.xlsx"
          instructions={[
            'Baixe o arquivo modelo e preencha a aba "Modelo".',
            'Os nomes de Operadora, Operação e BU devem ser exatamente como cadastrados.',
            'A aba "Valores de Referência" lista os nomes válidos.',
            'Campo "Ativo": Sim ou Não.',
          ]}
          onClose={() => setShowImportModal(false)}
          onImported={load}
        />
      )}
    </>
  );
}
