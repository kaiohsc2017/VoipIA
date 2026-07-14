import type { Dispatch, SetStateAction } from 'react';
import type { BusinessUnit, Client, NumberTestCreate, Operation, Segment } from '../api/types';

interface TestModalProps {
  editId: number | null;
  form: NumberTestCreate;
  setForm: Dispatch<SetStateAction<NumberTestCreate>>;
  bus: BusinessUnit[];
  clients: Client[];
  operations: Operation[];
  segments: Segment[];
  onClose: () => void;
  onSave: () => void;
}

/** Modal de criação/edição de teste de conectividade (Módulo 2). */
export function TestModal({
  editId, form, setForm, bus, clients, operations, segments, onClose, onSave,
}: TestModalProps) {
  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal modal-lg">
        <div className="modal-header">
          <h2>📞 {editId ? 'Editar' : 'Novo'} Teste de Conectividade</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          {(bus.length === 0 || clients.length === 0 || operations.length === 0 || segments.length === 0) && (
            <div style={{
              background: 'rgba(245, 158, 11, 0.1)', border: '1px solid rgba(245, 158, 11, 0.3)',
              borderRadius: 8, padding: '10px 14px', marginBottom: 16, fontSize: '0.85rem', color: '#f59e0b',
            }}>
              ⚠️ Cadastre BU, Clientes, Operações e Segmentos em <strong>Dados Mestres</strong> antes de criar testes.
            </div>
          )}
          <div className="form-group">
            <label className="form-label">Número de Telefone</label>
            <input type="tel" className="form-input" placeholder="+5511999999999"
              value={form.phoneNumber}
              onChange={e => setForm(f => ({ ...f, phoneNumber: e.target.value }))} />
          </div>
          <div className="form-grid">
            <div className="form-group">
              <label className="form-label">Business Unit</label>
              <select className="form-select" value={form.businessUnit.id}
                onChange={e => setForm(f => ({ ...f, businessUnit: { id: +e.target.value } }))}>
                <option value={0}>Selecione…</option>
                {bus.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label className="form-label">Segmento</label>
              <select className="form-select" value={form.segment.id}
                onChange={e => setForm(f => ({ ...f, segment: { id: +e.target.value } }))}>
                <option value={0}>Selecione…</option>
                {segments.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label className="form-label">Cliente</label>
              <select className="form-select" value={form.client.id}
                onChange={e => setForm(f => ({ ...f, client: { id: +e.target.value } }))}>
                <option value={0}>Selecione…</option>
                {clients.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label className="form-label">Operação</label>
              <select className="form-select" value={form.operation.id}
                onChange={e => setForm(f => ({ ...f, operation: { id: +e.target.value } }))}>
                <option value={0}>Selecione…</option>
                {operations.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
              </select>
            </div>
          </div>
          <div className="form-grid-3">
            <div className="form-group">
              <label className="form-label">Horário de Início</label>
              <input type="time" className="form-input" value={form.startTime?.slice(0, 5)}
                onChange={e => setForm(f => ({ ...f, startTime: e.target.value + ':00' }))} />
            </div>
            <div className="form-group">
              <label className="form-label">Intervalo (min)</label>
              <input type="number" className="form-input" min={1} value={form.intervalMinutes}
                onChange={e => setForm(f => ({ ...f, intervalMinutes: +e.target.value }))} />
            </div>
            <div className="form-group">
              <label className="form-label">Quantidade</label>
              <input type="number" className="form-input" min={1} value={form.quantity}
                onChange={e => setForm(f => ({ ...f, quantity: +e.target.value }))} />
            </div>
          </div>
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" onClick={onSave}>{editId ? 'Salvar Alterações' : 'Criar Teste'}</button>
        </div>
      </div>
    </div>
  );
}
