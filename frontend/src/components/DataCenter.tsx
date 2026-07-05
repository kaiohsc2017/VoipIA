import { useEffect, useState } from 'react';
import api from '../api/client';
import type {
  PhoneNumber, PhoneNumberRequest, PhoneNumberSaveResult, NumberType,
  BusinessUnit, Client, Operation, Segment,
} from '../api/types';

const TYPE_LABEL: Record<NumberType, string> = {
  DDR: 'DDR',
  ZERO_OITO_ZERO_ZERO: '0800',
  WHATSAPP: 'WhatsApp',
};

const TYPE_BADGE: Record<NumberType, string> = {
  DDR: 'badge-gray',
  ZERO_OITO_ZERO_ZERO: 'badge-warning',
  WHATSAPP: 'badge-success',
};

const EMPTY_FORM: PhoneNumberRequest = {
  phoneNumber: '', numberType: 'DDR', businessUnitId: 0,
  clientId: undefined, newClientName: undefined,
  operationId: undefined, segmentId: undefined,
  observation: '', isActive: true,
};

export default function DataCenter() {
  const [numbers, setNumbers] = useState<PhoneNumber[]>([]);
  const [bus, setBus] = useState<BusinessUnit[]>([]);
  const [clients, setClients] = useState<Client[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [segments, setSegments] = useState<Segment[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [form, setForm] = useState<PhoneNumberRequest>({ ...EMPTY_FORM });
  const [newClientMode, setNewClientMode] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = () => {
    setLoading(true);
    api.get<PhoneNumber[]>('/phone-numbers')
      .then(r => setNumbers(r.data ?? []))
      .catch(() => setNumbers([]))
      .finally(() => setLoading(false));
  };

  const loadMasterData = () => {
    Promise.all([
      api.get<BusinessUnit[]>('/business-units?active=true'),
      api.get<Client[]>('/clients?active=true'),
      api.get<Operation[]>('/operations?active=true'),
      api.get<Segment[]>('/segments?active=true'),
    ]).then(([b, c, o, s]) => {
      setBus(b.data); setClients(c.data);
      setOperations(o.data); setSegments(s.data);
    }).catch(err => console.error('Erro ao carregar dados mestres:', err));
  };

  useEffect(() => { load(); loadMasterData(); }, []);

  const openCreate = () => {
    setEditId(null);
    setForm({ ...EMPTY_FORM });
    setNewClientMode(false);
    setShowModal(true);
  };

  const openEdit = (n: PhoneNumber) => {
    setEditId(n.id);
    setForm({
      phoneNumber: n.phoneNumber,
      numberType: n.numberType,
      businessUnitId: n.businessUnit.id,
      clientId: n.client.id,
      newClientName: undefined,
      operationId: n.operation?.id,
      segmentId: n.segment?.id,
      observation: n.observation ?? '',
      isActive: n.isActive,
    });
    setNewClientMode(false);
    setShowModal(true);
  };

  const save = async () => {
    if (!form.phoneNumber?.trim()) { alert('Informe o número de telefone.'); return; }
    if (!form.businessUnitId) { alert('Selecione a Business Unit.'); return; }
    if (!newClientMode && !form.clientId) { alert('Selecione o Cliente ou marque "Cliente novo".'); return; }
    if (newClientMode && !form.newClientName?.trim()) { alert('Informe o nome do novo Cliente.'); return; }

    const body: PhoneNumberRequest = {
      ...form,
      clientId: newClientMode ? undefined : form.clientId,
      newClientName: newClientMode ? form.newClientName?.trim() : undefined,
    };

    setSaving(true);
    try {
      const res = editId
        ? await api.put<PhoneNumberSaveResult>(`/phone-numbers/${editId}`, body)
        : await api.post<PhoneNumberSaveResult>('/phone-numbers', body);
      setShowModal(false);
      load();
      if (res.data.clientCreated) {
        alert(`Cliente "${res.data.phoneNumber.client.name}" criado automaticamente — complete o cadastro na tela Clientes.`);
      } else if (res.data.usedSystemDefaultTemplate) {
        alert('Teste de conectividade criado com o template padrão do sistema (08:00 / 60min / 3×) — o Segmento não tem template próprio configurado.');
      }
    } catch (err: any) {
      alert(err?.response?.data?.error ?? 'Erro ao salvar o número.');
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (n: PhoneNumber) => {
    try {
      await api.put(`/phone-numbers/${n.id}`, {
        phoneNumber: n.phoneNumber, numberType: n.numberType,
        businessUnitId: n.businessUnit.id, clientId: n.client.id,
        operationId: n.operation?.id, segmentId: n.segment?.id,
        observation: n.observation, isActive: !n.isActive,
      });
      load();
    } catch (err: any) {
      alert(err?.response?.data?.error ?? 'Erro ao alterar status.');
    }
  };

  const remove = async (n: PhoneNumber) => {
    if (!confirm(`Remover o número "${n.phoneNumber}"? O teste de conectividade vinculado (se houver) será desativado, mas o histórico é preservado.`)) return;
    try {
      await api.delete(`/phone-numbers/${n.id}`);
      load();
    } catch (err: any) {
      alert(err?.response?.data?.error ?? 'Erro ao remover.');
    }
  };

  const pendingCount = numbers.filter(n => !n.operation || !n.segment).length;

  return (
    <>
      <div className="page-header">
        <h1>🖧 DATACENTER</h1>
        <p>Cadastro central de números (DDR, 0800, WhatsApp) — alimenta automaticamente Clientes e o Módulo Conectividade</p>
      </div>
      <div className="page-body">

        <div className="toolbar">
          <div className="toolbar-left">
            <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
              {numbers.length} número{numbers.length !== 1 ? 's' : ''} cadastrado{numbers.length !== 1 ? 's' : ''}
              {pendingCount > 0 && (
                <span className="badge badge-warning" style={{ marginLeft: 8 }}>
                  {pendingCount} pendente{pendingCount !== 1 ? 's' : ''}
                </span>
              )}
            </span>
          </div>
          <div className="toolbar-right">
            <button className="btn btn-primary" onClick={openCreate}>＋ Novo Número</button>
          </div>
        </div>

        {loading ? (
          <div className="loading-state"><div className="spinner" />Carregando…</div>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>Número</th>
                  <th>Tipo</th>
                  <th>BU</th>
                  <th>Cliente</th>
                  <th>Operação</th>
                  <th>Segmento</th>
                  <th>Status</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {numbers.length === 0 ? (
                  <tr><td colSpan={9} className="table-empty">
                    Nenhum número cadastrado.<br />
                    <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                      Clique em "＋ Novo Número" para adicionar.
                    </span>
                  </td></tr>
                ) : numbers.map(n => (
                  <tr key={n.id}>
                    <td className="td-muted">{n.id}</td>
                    <td className="mono">{n.phoneNumber}</td>
                    <td><span className={`badge ${TYPE_BADGE[n.numberType]}`}>{TYPE_LABEL[n.numberType]}</span></td>
                    <td>{n.businessUnit.name}</td>
                    <td>{n.client.name}</td>
                    <td>{n.operation?.name ?? <span style={{ color: '#f59e0b' }}>— pendente —</span>}</td>
                    <td>{n.segment?.name ?? <span style={{ color: '#f59e0b' }}>— pendente —</span>}</td>
                    <td>
                      <span className={`badge ${n.isActive ? 'badge-success' : 'badge-gray'}`}>
                        {n.isActive ? 'Ativo' : 'Inativo'}
                      </span>
                    </td>
                    <td>
                      <div className="flex gap-1">
                        <button className="btn btn-ghost btn-sm btn-icon" onClick={() => openEdit(n)} title="Editar">✏️</button>
                        <button className="btn btn-ghost btn-sm btn-icon" onClick={() => toggleActive(n)} title={n.isActive ? 'Desativar' : 'Ativar'}>
                          {n.isActive ? '⏸' : '▶️'}
                        </button>
                        <button className="btn btn-danger btn-sm btn-icon" onClick={() => remove(n)} title="Remover">🗑️</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showModal && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal modal-lg">
            <div className="modal-header">
              <h2>🖧 {editId ? 'Editar' : 'Novo'} Número — DATACENTER</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Número de Telefone</label>
                  <input type="tel" className="form-input" placeholder="+5511999999999 ou 0800..."
                    value={form.phoneNumber}
                    onChange={e => setForm(f => ({ ...f, phoneNumber: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Tipo</label>
                  <select className="form-select" value={form.numberType}
                    onChange={e => setForm(f => ({ ...f, numberType: e.target.value as NumberType }))}>
                    <option value="DDR">DDR</option>
                    <option value="ZERO_OITO_ZERO_ZERO">0800</option>
                    <option value="WHATSAPP">WhatsApp</option>
                  </select>
                </div>
              </div>

              <div className="form-group">
                <label className="form-label">Business Unit</label>
                <select className="form-select" value={form.businessUnitId}
                  onChange={e => setForm(f => ({ ...f, businessUnitId: +e.target.value }))}>
                  <option value={0}>Selecione…</option>
                  {bus.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              </div>

              <div className="form-group">
                <div className="flex gap-1" style={{ alignItems: 'center', marginBottom: 6 }}>
                  <label className="form-label" style={{ marginBottom: 0 }}>Cliente</label>
                  <button type="button" className="btn btn-ghost btn-sm" style={{ marginLeft: 'auto' }}
                    onClick={() => { setNewClientMode(m => !m); setForm(f => ({ ...f, clientId: undefined, newClientName: undefined })); }}>
                    {newClientMode ? '↩ Cliente existente' : '＋ Cliente novo'}
                  </button>
                </div>
                {newClientMode ? (
                  <input type="text" className="form-input" placeholder="Nome do cliente novo"
                    value={form.newClientName ?? ''}
                    onChange={e => setForm(f => ({ ...f, newClientName: e.target.value }))} />
                ) : (
                  <select className="form-select" value={form.clientId ?? 0}
                    onChange={e => setForm(f => ({ ...f, clientId: +e.target.value || undefined }))}>
                    <option value={0}>Selecione…</option>
                    {clients.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                )}
              </div>

              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Operação <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>(opcional)</span></label>
                  <select className="form-select" value={form.operationId ?? 0}
                    onChange={e => setForm(f => ({ ...f, operationId: +e.target.value || undefined }))}>
                    <option value={0}>Deixar pendente…</option>
                    {operations.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                </div>
                <div className="form-group">
                  <label className="form-label">Segmento <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>(opcional)</span></label>
                  <select className="form-select" value={form.segmentId ?? 0}
                    onChange={e => setForm(f => ({ ...f, segmentId: +e.target.value || undefined }))}>
                    <option value={0}>Deixar pendente…</option>
                    {segments.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select>
                </div>
              </div>

              {form.numberType !== 'WHATSAPP' && (!form.operationId || !form.segmentId) && (
                <div style={{
                  background: 'rgba(245, 158, 11, 0.1)', border: '1px solid rgba(245, 158, 11, 0.3)',
                  borderRadius: 8, padding: '10px 14px', marginBottom: 16, fontSize: '0.85rem', color: '#f59e0b',
                }}>
                  ⚠️ Sem Operação e Segmento, este número não gera teste de conectividade agora — fica pendente até ser completado em Clientes.
                </div>
              )}
              {form.numberType === 'WHATSAPP' && (
                <div style={{
                  background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.3)',
                  borderRadius: 8, padding: '10px 14px', marginBottom: 16, fontSize: '0.85rem', color: '#6ee7b7',
                }}>
                  ℹ️ Números WhatsApp não geram teste automático de conectividade.
                </div>
              )}

              <div className="form-group">
                <label className="form-label">Observação</label>
                <input type="text" className="form-input" placeholder="Observações adicionais"
                  value={form.observation ?? ''}
                  onChange={e => setForm(f => ({ ...f, observation: e.target.value }))} />
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={saving}>
                {saving ? 'Salvando…' : editId ? 'Salvar Alterações' : 'Criar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
