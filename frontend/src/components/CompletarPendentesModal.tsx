import { useEffect, useState } from 'react';
import api from '../api/client';
import type { PhoneNumber, Operation, Segment } from '../api/types';

interface CompletarPendentesModalProps {
  clientId: number;
  clientName: string;
  onClose: () => void;
}

const TYPE_LABEL: Record<string, string> = {
  DDR: 'DDR', ZERO_OITO_ZERO_ZERO: '0800', WHATSAPP: 'WhatsApp',
};

/**
 * CompletarPendentesModal — lista os números do DATACENTER vinculados a um
 * Cliente que ainda estão sem Operação/Segmento definidos ("pendentes") e
 * permite completá-los sem sair da tela de Clientes.
 */
export default function CompletarPendentesModal({ clientId, clientName, onClose }: CompletarPendentesModalProps) {
  const [numbers, setNumbers] = useState<PhoneNumber[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [segments, setSegments] = useState<Segment[]>([]);
  const [draft, setDraft] = useState<Record<number, { operationId?: number; segmentId?: number }>>({});
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState<number | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([
      api.get<PhoneNumber[]>(`/phone-numbers?clientId=${clientId}`),
      api.get<Operation[]>('/operations?active=true'),
      api.get<Segment[]>('/segments?active=true'),
    ]).then(([n, o, s]) => {
      const pending = n.data.filter(p => !p.operation || !p.segment);
      setNumbers(pending);
      setOperations(o.data);
      setSegments(s.data);
      setDraft(Object.fromEntries(pending.map(p => [p.id, { operationId: p.operation?.id, segmentId: p.segment?.id }])));
    }).finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, [clientId]);

  const save = async (n: PhoneNumber) => {
    const d = draft[n.id];
    if (!d?.operationId || !d?.segmentId) {
      alert('Selecione Operação e Segmento antes de salvar.');
      return;
    }
    setSavingId(n.id);
    try {
      await api.put(`/phone-numbers/${n.id}`, {
        phoneNumber: n.phoneNumber, numberType: n.numberType,
        businessUnitId: n.businessUnit.id, clientId: n.client.id,
        operationId: d.operationId, segmentId: d.segmentId,
        observation: n.observation, isActive: n.isActive,
      });
      load();
    } catch (err: any) {
      alert(err?.response?.data?.error ?? 'Erro ao completar o cadastro.');
    } finally {
      setSavingId(null);
    }
  };

  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" style={{ maxWidth: 720, width: '95vw' }}>
        <div className="modal-header">
          <h2>📋 Completar cadastro — {clientName}</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          {loading ? (
            <div className="loading-state"><div className="spinner" />Carregando…</div>
          ) : numbers.length === 0 ? (
            <div className="table-empty">Nenhum número pendente para este cliente. 🎉</div>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Número</th>
                    <th>Tipo</th>
                    <th>Operação</th>
                    <th>Segmento</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {numbers.map(n => (
                    <tr key={n.id}>
                      <td className="mono">{n.phoneNumber}</td>
                      <td>{TYPE_LABEL[n.numberType]}</td>
                      <td>
                        <select className="form-select" value={draft[n.id]?.operationId ?? 0}
                          onChange={e => setDraft(d => ({ ...d, [n.id]: { ...d[n.id], operationId: +e.target.value || undefined } }))}>
                          <option value={0}>Selecione…</option>
                          {operations.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                        </select>
                      </td>
                      <td>
                        <select className="form-select" value={draft[n.id]?.segmentId ?? 0}
                          onChange={e => setDraft(d => ({ ...d, [n.id]: { ...d[n.id], segmentId: +e.target.value || undefined } }))}>
                          <option value={0}>Selecione…</option>
                          {segments.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                        </select>
                      </td>
                      <td>
                        <button className="btn btn-primary btn-sm" disabled={savingId === n.id} onClick={() => save(n)}>
                          {savingId === n.id ? 'Salvando…' : 'Salvar'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}
