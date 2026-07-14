import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { AlertCall, AlertContact, Operation, PageResponse } from '../api/types';
import { AuthedAudio } from './AuthedAudio';

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

// ─── KPI cards do Módulo 3 ────────────────────────────────────────────────────

interface AlertStats {
  totalAlerts: number;
  answered: number;
  notAnswered: number;
  failed: number;
  telegramSent: number;
  answeredRatePct: number;
  telegramSuccessRatePct: number;
}

function KpiBar() {
  const [stats, setStats] = useState<AlertStats | null>(null);
  const [period, setPeriod] = useState<'today' | 'week' | 'month'>('today');

  const load = (p: typeof period) => {
    api.get<AlertStats>(`/stats/alerts?period=${p}`).then(r => setStats(r.data));
  };

  useEffect(() => { load('today'); }, []);

  if (!stats) return null;

  return (
    <div style={{ marginBottom: 24 }}>
      <div className="flex gap-1" style={{ marginBottom: 12 }}>
        {(['today', 'week', 'month'] as const).map(p => (
          <button key={p} className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => { setPeriod(p); load(p); }}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
          </button>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 12 }}>
        {[
          { label: 'Total Alertas',   value: stats.totalAlerts,              color: '#007aff' },
          { label: 'Atendidas',       value: stats.answered,                 color: '#34c759' },
          { label: 'Não Atendidas',   value: stats.notAnswered,              color: '#ff9f0a' },
          { label: 'Falhas',          value: stats.failed,                   color: '#ff6b6b' },
          { label: 'Taxa Atendimento',value: `${stats.answeredRatePct}%`,    color: '#34c759' },
          { label: 'Telegram OK',     value: `${stats.telegramSuccessRatePct}%`, color: '#3b82f6' },
        ].map(kpi => (
          <div key={kpi.label} className="stat-card" style={{ padding: '14px 18px' }}>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 4 }}>{kpi.label}</div>
            <div style={{ fontSize: '1.5rem', fontWeight: 700, color: kpi.color }}>{kpi.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Player de áudio inline para alertas ─────────────────────────────────────

function AlertAudioPlayer({ alertId }: { alertId: number }) {
  const [show, setShow] = useState(false);
  if (!show) {
    return (
      <button
        className="btn btn-ghost btn-sm btn-icon"
        onClick={() => setShow(true)}
        title="Ouvir gravação"
      >▶️</button>
    );
  }
  return (
    <AuthedAudio
      path={`/alert-calls/${alertId}/audio`}
      autoPlay
      style={{ height: 28, minWidth: 180, maxWidth: 240 }}
      onError={() => setShow(false)}
    />
  );
}

// ─── Modal mensagem Telegram ──────────────────────────────────────────────────

function TelegramMessageModal({ message, onClose }: { message: string; onClose: () => void }) {
  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal modal-sm">
        <div className="modal-header">
          <h2>📨 Mensagem Telegram</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          <pre style={{
            background: 'rgba(0,0,0,0.2)', padding: 16, borderRadius: 8,
            whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            fontSize: '0.875rem', color: 'var(--text-primary)', lineHeight: 1.6,
            maxHeight: 400, overflowY: 'auto',
          }}>
            {message}
          </pre>
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}

// ─── Módulo Alertas principal ─────────────────────────────────────────────────

export default function ModuloAlertas() {
  const [tab, setTab] = useState<'alerts' | 'contacts'>('alerts');
  const [alerts, setAlerts] = useState<AlertCall[]>([]);
  const [contacts, setContacts] = useState<AlertContact[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [showModal, setShowModal] = useState(false);
  const [editContact, setEditContact] = useState<Partial<AlertContact>>({ ...EMPTY_CONTACT });
  const [telegramMsg, setTelegramMsg] = useState<string | null>(null);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [filterOperationId, setFilterOperationId] = useState<string>('');

  useEffect(() => {
    api.get<Operation[]>('/operations')
      .then(r => setOperations(r.data.filter(op => op.isActive)))
      .catch(err => console.error('Erro ao carregar operações:', err));
  }, []);

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
    const qs = filterOperationId ? `?operationId=${filterOperationId}` : '';
    api.get<AlertContact[]>(`/alert-contacts${qs}`)
      .then(r => setContacts(r.data))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (tab === 'alerts') loadAlerts();
    else loadContacts();
  }, [tab, filterOperationId]);

  const openCreate = () => {
    setEditContact({ ...EMPTY_CONTACT, priorityOrder: contacts.length + 1 });
    setShowModal(true);
  };

  const openEdit = (c: AlertContact) => {
    setEditContact({ ...c });
    setShowModal(true);
  };

  const saveContact = async () => {
    if (!editContact.name?.trim()) { alert('Informe o nome do contato.'); return; }
    if (!editContact.phoneNumber?.trim()) { alert('Informe o telefone do contato.'); return; }
    if (!/^\+?[0-9]{10,15}$/.test(editContact.phoneNumber)) { alert('Telefone inválido. Ex: +5511999999999'); return; }
    if (!editContact.priorityOrder) { alert('Informe a ordem de prioridade.'); return; }
    
    try {
      if (editContact.id) { await api.put(`/alert-contacts/${editContact.id}`, editContact); }
      else { await api.post('/alert-contacts', editContact); }
      setShowModal(false);
      loadContacts();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar o contato.'));
    }
  };

  const deleteContact = async (id: number) => {
    if (!confirm('Remover este contato de plantão?')) return;
    try {
      await api.delete(`/alert-contacts/${id}`);
      loadContacts();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover o contato.'));
    }
  };

  return (
    <>
      <div className="page-header">
        <h1>🚨 Alertas Zabbix</h1>
        <p>Histórico de alertas de infraestrutura e contatos de plantão</p>
      </div>
      <div className="page-body">

        {/* KPIs — sempre visíveis no topo */}
        <KpiBar />

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
                      <th>Áudio</th>
                      <th>Telegram</th>
                      <th>Trigger ID</th>
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.length === 0 ? (
                      <tr><td colSpan={10} className="table-empty">Nenhum alerta registrado ainda</td></tr>
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
                            ? <span className={`badge ${SEVERITY_CLASS[a.zabbixSeverity] ?? 'badge-gray'}`}>{a.zabbixSeverity}</span>
                            : <span className="text-muted">—</span>}
                        </td>
                        <td><span className="chip">{a.zabbixHost || '—'}</span></td>
                        <td style={{ maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          <span title={a.zabbixIncidentSummary}>{a.zabbixIncidentSummary}</span>
                        </td>
                        <td>
                          {a.audioFilePath
                            ? <AlertAudioPlayer alertId={a.id} />
                            : <span className="text-muted">—</span>}
                        </td>
                        <td>
                          {a.telegramSentAt ? (
                            <div className="flex gap-1" style={{ alignItems: 'center' }}>
                              <span className="badge badge-success">✓ Enviado</span>
                              {a.telegramMessageContent && (
                                <button
                                  className="btn btn-ghost btn-sm btn-icon"
                                  onClick={() => setTelegramMsg(a.telegramMessageContent!)}
                                  title="Ver mensagem"
                                >👁️</button>
                              )}
                            </div>
                          ) : (
                            <span className="badge badge-gray">Não enviado</span>
                          )}
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
              <div className="toolbar-left" style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
                  {contacts.filter(c => c.isActive).length} contato{contacts.filter(c => c.isActive).length !== 1 ? 's' : ''} ativo{contacts.filter(c => c.isActive).length !== 1 ? 's' : ''}
                </span>
                <select 
                  className="form-select" 
                  style={{ width: 200 }}
                  value={filterOperationId} 
                  onChange={e => setFilterOperationId(e.target.value)}
                >
                  <option value="">Todas as Operações</option>
                  {operations.map(op => <option key={op.id} value={op.id}>{op.name}</option>)}
                </select>
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
                      <th>Operação</th>
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
                          <td style={{ textAlign: 'center' }}><span className="chip">#{c.priorityOrder}</span></td>
                          <td>{c.name}</td>
                          <td className="mono">{c.phoneNumber}</td>
                          <td>
                            {c.operationId 
                              ? <span className="chip">{operations.find(o => o.id === c.operationId)?.name || `ID ${c.operationId}`}</span> 
                              : <span className="text-muted">—</span>}
                          </td>
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
                <input type="text" className="form-input" placeholder="João Silva"
                  value={editContact.name ?? ''}
                  onChange={e => setEditContact(c => ({ ...c, name: e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Telefone</label>
                <input type="tel" className="form-input" placeholder="+5511999999999"
                  value={editContact.phoneNumber ?? ''}
                  onChange={e => setEditContact(c => ({ ...c, phoneNumber: e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Operação</label>
                <select className="form-select"
                  value={editContact.operationId ?? ''}
                  onChange={e => setEditContact(c => ({ ...c, operationId: e.target.value ? Number(e.target.value) : undefined }))}>
                  <option value="">(Global / Todas)</option>
                  {operations.map(op => <option key={op.id} value={op.id}>{op.name}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label className="form-label">Ordem de Prioridade</label>
                <input type="number" className="form-input" min={1}
                  value={editContact.priorityOrder ?? 1}
                  onChange={e => setEditContact(c => ({ ...c, priorityOrder: +e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Status</label>
                <select className="form-select"
                  value={editContact.isActive ? 'true' : 'false'}
                  onChange={e => setEditContact(c => ({ ...c, isActive: e.target.value === 'true' }))}>
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

      {/* Modal mensagem Telegram */}
      {telegramMsg && (
        <TelegramMessageModal message={telegramMsg} onClose={() => setTelegramMsg(null)} />
      )}
    </>
  );
}
