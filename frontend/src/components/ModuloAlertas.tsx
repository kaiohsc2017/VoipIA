import { useEffect, useState } from 'react';
import api from '../api/client';
import type { AlertCall, AlertContact, PageResponse } from '../api/types';

const CALL_STATUS_CLASS: Record<string, string> = {
  ATENDIDA:     'badge-success',
  NAO_ATENDIDA: 'badge-warning',
  FALHA:        'badge-danger',
  PENDENTE:     'badge-accent',
};

const SEVERITY_CLASS: Record<string, string> = {
  Disaster: 'badge-danger',
  High:     'badge-warning',
  Average:  'badge-info',
  Warning:  'badge-gray',
};

function formatDate(iso?: string) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

const EMPTY_CONTACT: Partial<AlertContact> = {
  name: '', phoneNumber: '', isActive: true, priorityOrder: 1,
};

export default function ModuloAlertas() {
  const [tab, setTab] = useState<'alerts' | 'contacts'>('alerts');
  const [alerts, setAlerts] = useState<AlertCall[]>([]);
  const [contacts, setContacts] = useState<AlertContact[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [showModal, setShowModal] = useState(false);
  const [editContact, setEditContact] = useState<Partial<AlertContact>>({ ...EMPTY_CONTACT });

  const loadAlerts = (p = 0) => {
    setLoading(true);
    api.get<PageResponse<AlertCall>>(`/alert-calls?page=${p}&size=20`)
      .then(r => {
        setAlerts(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .finally(() => setLoading(false));
  };

  const loadContacts = () => {
    setLoading(true);
    api.get<AlertContact[]>('/alert-contacts')
      .then(r => setContacts(r.data))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (tab === 'alerts') loadAlerts();
    else loadContacts();
  }, [tab]);

  const openCreate = () => {
    setEditContact({ ...EMPTY_CONTACT, priorityOrder: contacts.length + 1 });
    setShowModal(true);
  };

  const openEdit = (c: AlertContact) => {
    setEditContact({ ...c });
    setShowModal(true);
  };

  const saveContact = async () => {
    if (editContact.id) {
      await api.put(`/alert-contacts/${editContact.id}`, editContact);
    } else {
      await api.post('/alert-contacts', editContact);
    }
    setShowModal(false);
    loadContacts();
  };

  const deleteContact = async (id: number) => {
    if (confirm('Remover este contato de plantão?')) {
      await api.delete(`/alert-contacts/${id}`);
      loadContacts();
    }
  };

  return (
    <>
      <div className="page-header">
        <h1>🚨 Módulo 3 — Alertas Zabbix</h1>
        <p>Histórico de alertas de infraestrutura e contatos de plantão</p>
      </div>
      <div className="page-body">

        {/* Tabs */}
        <div className="flex gap-1" style={{ marginBottom: 20 }}>
          <button className={`btn ${tab === 'alerts' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('alerts')}>
            🚨 Histórico de Alertas
          </button>
          <button className={`btn ${tab === 'contacts' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('contacts')}>
            📱 Contatos de Plantão
          </button>
        </div>

        {/* ---- ALERTS TAB ---- */}
        {tab === 'alerts' && (
          <>
            {loading ? (
              <div className="loading-state"><div className="spinner" />Carregando alertas…</div>
            ) : (
              <div className="table-wrapper">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Data</th>
                      <th>Número Discado</th>
                      <th>Status Chamada</th>
                      <th>Severidade</th>
                      <th>Host</th>
                      <th>Incidente</th>
                      <th>Telegram</th>
                      <th>Trigger ID</th>
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.length === 0 ? (
                      <tr><td colSpan={9} className="table-empty">Nenhum alerta registrado ainda</td></tr>
                    ) : alerts.map(a => (
                      <tr key={a.id}>
                        <td className="td-muted">{a.id}</td>
                        <td className="td-muted">{formatDate(a.callDate)}</td>
                        <td className="mono">{a.phoneNumber}</td>
                        <td>
                          <span className={`badge ${CALL_STATUS_CLASS[a.callStatus] ?? 'badge-gray'}`}>
                            {a.callStatus}
                          </span>
                        </td>
                        <td>
                          {a.zabbixSeverity
                            ? <span className={`badge ${SEVERITY_CLASS[a.zabbixSeverity] ?? 'badge-gray'}`}>
                                {a.zabbixSeverity}
                              </span>
                            : <span className="text-muted">—</span>}
                        </td>
                        <td>
                          <span className="chip">{a.zabbixHost || '—'}</span>
                        </td>
                        <td style={{ maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          <span title={a.zabbixIncidentSummary}>{a.zabbixIncidentSummary}</span>
                        </td>
                        <td>
                          {a.telegramSentAt
                            ? <span className="badge badge-success">✓ Enviado</span>
                            : <span className="badge badge-gray">Não enviado</span>}
                        </td>
                        <td className="mono td-muted" style={{ fontSize: '0.72rem' }}>
                          {a.zabbixTriggerId?.slice(0, 10)}…
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="pagination">
                  <span className="pagination-info">Página {page + 1} de {totalPages}</span>
                  <div className="pagination-btns">
                    <button className="page-btn" disabled={page === 0} onClick={() => loadAlerts(page - 1)}>‹</button>
                    <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => loadAlerts(page + 1)}>›</button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {/* ---- CONTACTS TAB ---- */}
        {tab === 'contacts' && (
          <>
            <div className="toolbar">
              <div className="toolbar-left">
                <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
                  {contacts.filter(c => c.isActive).length} contato{contacts.filter(c => c.isActive).length !== 1 ? 's' : ''} ativo{contacts.filter(c => c.isActive).length !== 1 ? 's' : ''}
                </span>
              </div>
              <div className="toolbar-right">
                <button className="btn btn-primary" onClick={openCreate}>＋ Novo Contato</button>
              </div>
            </div>

            {loading ? (
              <div className="loading-state"><div className="spinner" />Carregando contatos…</div>
            ) : (
              <div className="table-wrapper">
                <table>
                  <thead>
                    <tr>
                      <th>Prioridade</th>
                      <th>Nome</th>
                      <th>Telefone</th>
                      <th>Status</th>
                      <th>Ações</th>
                    </tr>
                  </thead>
                  <tbody>
                    {contacts.length === 0 ? (
                      <tr><td colSpan={5} className="table-empty">
                        Nenhum contato de plantão cadastrado.<br />
                        <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                          Adicione contatos para receber ligações de alerta automáticas.
                        </span>
                      </td></tr>
                    ) : contacts
                      .sort((a, b) => a.priorityOrder - b.priorityOrder)
                      .map(c => (
                        <tr key={c.id}>
                          <td style={{ textAlign: 'center' }}>
                            <span className="chip">#{c.priorityOrder}</span>
                          </td>
                          <td>{c.name}</td>
                          <td className="mono">{c.phoneNumber}</td>
                          <td>
                            <span className={`badge ${c.isActive ? 'badge-success' : 'badge-gray'}`}>
                              {c.isActive ? 'Ativo' : 'Inativo'}
                            </span>
                          </td>
                          <td>
                            <div className="flex gap-1">
                              <button className="btn btn-ghost btn-sm btn-icon" onClick={() => openEdit(c)} title="Editar">✏️</button>
                              <button className="btn btn-danger btn-sm btn-icon" onClick={() => deleteContact(c.id)} title="Remover">🗑️</button>
                            </div>
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>

      {/* Modal contato */}
      {showModal && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal modal-sm">
            <div className="modal-header">
              <h2>📱 {editContact.id ? 'Editar' : 'Novo'} Contato de Plantão</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Nome</label>
                <input
                  type="text" className="form-input"
                  placeholder="João Silva"
                  value={editContact.name ?? ''}
                  onChange={e => setEditContact(c => ({ ...c, name: e.target.value }))}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Telefone</label>
                <input
                  type="tel" className="form-input"
                  placeholder="+5511999999999"
                  value={editContact.phoneNumber ?? ''}
                  onChange={e => setEditContact(c => ({ ...c, phoneNumber: e.target.value }))}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Ordem de Prioridade</label>
                <input
                  type="number" className="form-input" min={1}
                  value={editContact.priorityOrder ?? 1}
                  onChange={e => setEditContact(c => ({ ...c, priorityOrder: +e.target.value }))}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Status</label>
                <select
                  className="form-select"
                  value={editContact.isActive ? 'true' : 'false'}
                  onChange={e => setEditContact(c => ({ ...c, isActive: e.target.value === 'true' }))}
                >
                  <option value="true">Ativo</option>
                  <option value="false">Inativo</option>
                </select>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={saveContact}>
                {editContact.id ? 'Salvar' : 'Criar Contato'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
