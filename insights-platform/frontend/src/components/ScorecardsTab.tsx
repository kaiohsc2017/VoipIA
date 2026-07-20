import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { ScorecardDto, ScorecardItemDto } from '../api/types';

const EMPTY_ITEM: ScorecardItemDto = { ordem: 1, pergunta: '', peso: 1, notaMaxima: 10, isCritical: false };

interface ScorecardsTabProps {
  canWrite: boolean;
}

/** Aba "Fichas" — CRUD de fichas de avaliação de qualidade (Fase 1 do Quality
 * Management, V38). Só uma ficha pode estar ativa por vez; editar uma ficha com
 * avaliações já feitas cria uma nova versão no backend (transparente aqui). */
export function ScorecardsTab({ canWrite }: ScorecardsTabProps) {
  const [scorecards, setScorecards] = useState<ScorecardDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<ScorecardDto | null>(null);
  const [formName, setFormName] = useState('');
  const [formDescription, setFormDescription] = useState('');
  const [formItems, setFormItems] = useState<ScorecardItemDto[]>([{ ...EMPTY_ITEM }]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    api.get<ScorecardDto[]>('/insights/scorecards')
      .then(r => setScorecards(r.data))
      .catch(err => {
        console.error('Erro ao carregar fichas de avaliação:', err);
        setScorecards([]);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const startCreate = () => {
    setEditing({ id: 0, name: '', isActive: false, version: 1, items: [] });
    setFormName('');
    setFormDescription('');
    setFormItems([{ ...EMPTY_ITEM }]);
    setError('');
  };

  const startEdit = (scorecard: ScorecardDto) => {
    setEditing(scorecard);
    setFormName(scorecard.name);
    setFormDescription(scorecard.description ?? '');
    setFormItems(scorecard.items.map(i => ({ ...i })));
    setError('');
  };

  const cancelEdit = () => setEditing(null);

  const addItem = () => setFormItems(items => [...items, { ...EMPTY_ITEM, ordem: items.length + 1 }]);
  const removeItem = (index: number) => setFormItems(items => items.filter((_, i) => i !== index));
  const updateItem = (index: number, patch: Partial<ScorecardItemDto>) =>
    setFormItems(items => items.map((item, i) => (i === index ? { ...item, ...patch } : item)));

  const save = async () => {
    if (!formName.trim() || formItems.length === 0) {
      setError('Nome e ao menos um item são obrigatórios.');
      return;
    }
    setSaving(true);
    setError('');
    const body = { name: formName, description: formDescription || undefined, items: formItems };
    try {
      if (editing && editing.id) {
        await api.put(`/insights/scorecards/${editing.id}`, body);
      } else {
        await api.post('/insights/scorecards', body);
      }
      setEditing(null);
      load();
    } catch (err) {
      setError(getErrorMessage(err, 'Falha ao salvar ficha de avaliação'));
    } finally {
      setSaving(false);
    }
  };

  const activate = async (id: number) => {
    try {
      await api.post(`/insights/scorecards/${id}/activate`);
      load();
    } catch (err) {
      console.error('Erro ao ativar ficha:', err);
    }
  };

  const deactivate = async (id: number) => {
    try {
      await api.post(`/insights/scorecards/${id}/deactivate`);
      load();
    } catch (err) {
      console.error('Erro ao desativar ficha:', err);
    }
  };

  if (editing) {
    return (
      <>
        <div className="toolbar">
          <div className="toolbar-left">
            <h2 style={{ margin: 0 }}>{editing.id ? `Editar ficha: ${editing.name}` : 'Nova ficha de avaliação'}</h2>
          </div>
        </div>

        {error && <div className="alert alert-error" style={{ marginBottom: 16 }}>{error}</div>}

        <div className="form-grid" style={{ gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 20 }}>
          <div>
            <label className="form-label">Nome</label>
            <input className="form-input" value={formName} onChange={e => setFormName(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Descrição</label>
            <input className="form-input" value={formDescription} onChange={e => setFormDescription(e.target.value)} />
          </div>
        </div>

        <div className="table-wrapper" style={{ marginBottom: 16 }}>
          <table>
            <thead>
              <tr>
                <th style={{ width: 60 }}>Ordem</th>
                <th>Pergunta</th>
                <th style={{ width: 100 }}>Peso</th>
                <th style={{ width: 100 }}>Nota máx.</th>
                <th style={{ width: 90 }}>Crítico</th>
                <th style={{ width: 60 }}></th>
              </tr>
            </thead>
            <tbody>
              {formItems.map((item, index) => (
                <tr key={index}>
                  <td>
                    <input type="number" className="form-input" value={item.ordem}
                      onChange={e => updateItem(index, { ordem: Number(e.target.value) })} />
                  </td>
                  <td>
                    <input className="form-input" value={item.pergunta}
                      onChange={e => updateItem(index, { pergunta: e.target.value })} />
                  </td>
                  <td>
                    <input type="number" step="0.5" min="0" className="form-input" value={item.peso}
                      onChange={e => updateItem(index, { peso: Number(e.target.value) })} />
                  </td>
                  <td>
                    <input type="number" min="1" className="form-input" value={item.notaMaxima}
                      onChange={e => updateItem(index, { notaMaxima: Number(e.target.value) })} />
                  </td>
                  <td style={{ textAlign: 'center' }}>
                    <input type="checkbox" checked={item.isCritical}
                      onChange={e => updateItem(index, { isCritical: e.target.checked })} />
                  </td>
                  <td>
                    <button className="btn btn-ghost btn-sm" onClick={() => removeItem(index)} disabled={formItems.length <= 1}>✕</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-ghost btn-sm" onClick={addItem}>+ Adicionar item</button>
          <div style={{ flex: 1 }} />
          <button className="btn btn-ghost" onClick={cancelEdit} disabled={saving}>Cancelar</button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? 'Salvando…' : 'Salvar'}</button>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="toolbar">
        <div className="toolbar-left">
          <h2 style={{ margin: 0 }}>Fichas de Avaliação</h2>
        </div>
        {canWrite && (
          <div className="toolbar-right">
            <button className="btn btn-primary btn-sm" onClick={startCreate}>+ Nova ficha</button>
          </div>
        )}
      </div>

      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando fichas…</div>
      ) : (
        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Nome</th>
                <th>Versão</th>
                <th>Itens</th>
                <th>Status</th>
                {canWrite && <th style={{ width: 220 }}>Ações</th>}
              </tr>
            </thead>
            <tbody>
              {scorecards.length === 0 ? (
                <tr><td colSpan={canWrite ? 5 : 4} className="table-empty">Nenhuma ficha de avaliação cadastrada</td></tr>
              ) : scorecards.map(s => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td className="td-muted">v{s.version}</td>
                  <td className="td-muted">{s.items.length}</td>
                  <td>
                    {s.isActive
                      ? <span className="badge badge-success">Ativa</span>
                      : <span className="badge">Inativa</span>}
                  </td>
                  {canWrite && (
                    <td>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <button className="btn btn-ghost btn-sm" onClick={() => startEdit(s)}>Editar</button>
                        {s.isActive
                          ? <button className="btn btn-ghost btn-sm" onClick={() => deactivate(s.id)}>Desativar</button>
                          : <button className="btn btn-primary btn-sm" onClick={() => activate(s.id)}>Ativar</button>}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
