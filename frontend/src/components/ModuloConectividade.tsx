import { useEffect, useState } from 'react';
import api from '../api/client';
import type {
  NumberTest, NumberTestCreate, TestResult,
  BusinessUnit, Client, Operation, Segment,
  PageResponse,
} from '../api/types';

const STATUS_CLASS: Record<string, string> = {
  SUCESSO: 'badge-success', FALHA: 'badge-danger', OCUPADO: 'badge-warning',
  TIMEOUT: 'badge-warning', SEM_RESPOSTA: 'badge-gray',
  INVALIDO: 'badge-danger', INDISPONIVEL: 'badge-danger', RECUSADO: 'badge-danger',
};

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

const EMPTY_FORM: NumberTestCreate = {
  phoneNumber: '', businessUnit: { id: 0 }, client: { id: 0 },
  operation: { id: 0 }, segment: { id: 0 },
  startTime: '08:00:00', intervalMinutes: 60, quantity: 3, isActive: true,
};

export default function ModuloConectividade() {
  const [tab, setTab] = useState<'tests' | 'results'>('tests');
  const [tests, setTests] = useState<NumberTest[]>([]);
  const [results, setResults] = useState<TestResult[]>([]);
  const [bus, setBus] = useState<BusinessUnit[]>([]);
  const [clients, setClients] = useState<Client[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [segments, setSegments] = useState<Segment[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState<NumberTestCreate>({ ...EMPTY_FORM });
  const [editId, setEditId] = useState<number | null>(null);
  const [resPage, setResPage] = useState(0);
  const [resTotalPages, setResTotalPages] = useState(1);
  const [filterStatus, setFilterStatus] = useState('');

  const loadTests = () => {
    setLoading(true);
    api.get<NumberTest[]>('/number-tests')
      .then(r => setTests(r.data))
      .finally(() => setLoading(false));
  };

  const loadResults = (p = 0, status = '') => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '30' });
    if (status) params.set('status', status);
    api.get<PageResponse<TestResult>>(`/test-results?${params}`)
      .then(r => {
        setResults(r.data.content ?? []);
        setResTotalPages(r.data.totalPages);
        setResPage(r.data.number);
      })
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
    });
  };

  useEffect(() => {
    loadMasterData();
    if (tab === 'tests') loadTests();
    else loadResults();
  }, [tab]);

  const openCreate = () => { setEditId(null); setForm({ ...EMPTY_FORM }); setShowModal(true); };
  const openEdit = (t: NumberTest) => {
    setEditId(t.id);
    setForm({
      phoneNumber: t.phoneNumber,
      businessUnit: { id: t.businessUnit.id },
      client: { id: t.client.id },
      operation: { id: t.operation.id },
      segment: { id: t.segment.id },
      startTime: t.startTime,
      intervalMinutes: t.intervalMinutes,
      quantity: t.quantity,
      isActive: t.isActive,
    });
    setShowModal(true);
  };

  const save = async () => {
    if (editId) {
      await api.put(`/number-tests/${editId}`, form);
    } else {
      await api.post('/number-tests', form);
    }
    setShowModal(false);
    loadTests();
  };

  const toggleActive = async (t: NumberTest) => {
    await api.patch(`/number-tests/${t.id}/active?active=${!t.isActive}`);
    loadTests();
  };

  const deleteTest = async (id: number) => {
    if (confirm('Remover este teste?')) {
      await api.delete(`/number-tests/${id}`);
      loadTests();
    }
  };

  return (
    <>
      <div className="page-header">
        <h1>📞 Módulo 2 — Testes de Conectividade</h1>
        <p>Gerenciamento de números a testar e histórico de resultados</p>
      </div>
      <div className="page-body">

        {/* Tabs */}
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20 }}>
          <button className={`btn ${tab === 'tests' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('tests')}>
            📋 Testes Cadastrados
          </button>
          <button className={`btn ${tab === 'results' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('results')}>
            📊 Resultados
          </button>
        </div>

        {/* ---- TESTS TAB ---- */}
        {tab === 'tests' && (
          <>
            <div className="toolbar">
              <div className="toolbar-left">
                <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
                  {tests.length} número{tests.length !== 1 ? 's' : ''} cadastrado{tests.length !== 1 ? 's' : ''}
                </span>
              </div>
              <div className="toolbar-right">
                <button className="btn btn-primary" onClick={openCreate}>＋ Novo Teste</button>
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
                      <th>BU</th>
                      <th>Cliente</th>
                      <th>Operação</th>
                      <th>Segmento</th>
                      <th>Início</th>
                      <th>Intervalo</th>
                      <th>Qtd</th>
                      <th>Status</th>
                      <th>Ações</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tests.length === 0 ? (
                      <tr><td colSpan={11} className="table-empty">Nenhum teste cadastrado</td></tr>
                    ) : tests.map(t => (
                      <tr key={t.id}>
                        <td className="td-muted">{t.id}</td>
                        <td className="mono">{t.phoneNumber}</td>
                        <td>{t.businessUnit?.name}</td>
                        <td>{t.client?.name}</td>
                        <td>{t.operation?.name}</td>
                        <td>{t.segment?.name}</td>
                        <td className="td-muted">{t.startTime?.slice(0, 5)}</td>
                        <td className="td-muted">{t.intervalMinutes}min</td>
                        <td className="td-muted">{t.quantity}×</td>
                        <td>
                          <span className={`badge ${t.isActive ? 'badge-success' : 'badge-gray'}`}>
                            {t.isActive ? 'Ativo' : 'Inativo'}
                          </span>
                        </td>
                        <td>
                          <div className="flex gap-1">
                            <button className="btn btn-ghost btn-sm btn-icon" onClick={() => openEdit(t)} title="Editar">✏️</button>
                            <button className="btn btn-ghost btn-sm btn-icon" onClick={() => toggleActive(t)} title={t.isActive ? 'Pausar' : 'Ativar'}>
                              {t.isActive ? '⏸' : '▶️'}
                            </button>
                            <button className="btn btn-danger btn-sm btn-icon" onClick={() => deleteTest(t.id)} title="Remover">🗑️</button>
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

        {/* ---- RESULTS TAB ---- */}
        {tab === 'results' && (
          <>
            <div className="toolbar">
              <div className="toolbar-left">
                <select
                  className="form-select"
                  style={{ width: 180 }}
                  value={filterStatus}
                  onChange={e => { setFilterStatus(e.target.value); loadResults(0, e.target.value); }}
                >
                  <option value="">Todos os status</option>
                  {['SUCESSO','FALHA','OCUPADO','TIMEOUT','SEM_RESPOSTA','INVALIDO','INDISPONIVEL','RECUSADO'].map(s => (
                    <option key={s} value={s}>{s}</option>
                  ))}
                </select>
              </div>
            </div>

            {loading ? (
              <div className="loading-state"><div className="spinner" />Carregando resultados…</div>
            ) : (
              <div className="table-wrapper">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Execução</th>
                      <th>Número (test ID)</th>
                      <th>Status</th>
                      <th>Código SIP</th>
                      <th>Descrição SIP</th>
                      <th>Ordem</th>
                      <th>Call ID Asterisk</th>
                    </tr>
                  </thead>
                  <tbody>
                    {results.length === 0 ? (
                      <tr><td colSpan={8} className="table-empty">Nenhum resultado encontrado</td></tr>
                    ) : results.map(r => (
                      <tr key={r.id}>
                        <td className="td-muted">{r.id}</td>
                        <td className="td-muted">{formatDate(r.executedAt)}</td>
                        <td className="mono">#{r.numberTest?.id}</td>
                        <td><span className={`badge ${STATUS_CLASS[r.status] ?? 'badge-gray'}`}>{r.status}</span></td>
                        <td className="td-muted">{r.sipResponseCode ?? '—'}</td>
                        <td className="td-muted">{r.sipResponseReason || '—'}</td>
                        <td className="td-muted">{r.executionOrder}ª</td>
                        <td className="mono td-muted" style={{ fontSize: '0.72rem' }}>
                          {r.asteriskCallId ? r.asteriskCallId.slice(0, 16) + '…' : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="pagination">
                  <span className="pagination-info">Página {resPage + 1} de {resTotalPages}</span>
                  <div className="pagination-btns">
                    <button className="page-btn" disabled={resPage === 0} onClick={() => loadResults(resPage - 1, filterStatus)}>‹</button>
                    <button className="page-btn" disabled={resPage >= resTotalPages - 1} onClick={() => loadResults(resPage + 1, filterStatus)}>›</button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {/* Modal novo/editar teste */}
      {showModal && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal modal-lg">
            <div className="modal-header">
              <h2>📞 {editId ? 'Editar' : 'Novo'} Teste de Conectividade</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Número de Telefone</label>
                <input
                  type="tel" className="form-input"
                  placeholder="+5511999999999"
                  value={form.phoneNumber}
                  onChange={e => setForm(f => ({ ...f, phoneNumber: e.target.value }))}
                />
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
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save}>
                {editId ? 'Salvar Alterações' : 'Criar Teste'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
