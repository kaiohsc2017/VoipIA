import { useEffect, useState, useRef } from 'react';
import * as XLSX from 'xlsx';
import api, { getErrorMessage } from '../api/client';
import type {
  NumberTest, NumberTestCreate, TestResult,
  BusinessUnit, Client, Operation, Segment,
  PageResponse,
} from '../api/types';
import { HistoricoModal } from './HistoricoModal';
import { DashboardKPIs } from './DashboardKPIs';
import { TestModal } from './TestModal';
import { STATUS_CLASS, formatDate, nextExecution, getPeriodRange } from './connectivityHelpers';

const EMPTY_FORM: NumberTestCreate = {
  phoneNumber: '', businessUnit: { id: 0 }, client: { id: 0 },
  operation: { id: 0 }, segment: { id: 0 },
  startTime: '08:00:00', intervalMinutes: 60, quantity: 3, isActive: true,
};

// ─── Módulo Principal ─────────────────────────────────────────────────────────

export default function ModuloConectividade() {
  const [tab, setTab] = useState<'tests' | 'results' | 'dashboard'>('tests');
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
  const [filterBu, setFilterBu] = useState('');
  const [filterClient, setFilterClient] = useState('');
  const [filterOperation, setFilterOperation] = useState('');
  const [filterSegment, setFilterSegment] = useState('');
  const [filterPeriod, setFilterPeriod] = useState<'today' | 'week' | 'month' | 'custom'>('month');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [histTest, setHistTest] = useState<NumberTest | null>(null);
  const [exporting, setExporting] = useState(false);
  const [showImport, setShowImport]         = useState(false);
  const [importFile, setImportFile]         = useState<File | null>(null);
  const [importing, setImporting]           = useState(false);
  const [importResult, setImportResult]     = useState<{ importados: number; erros: number; detalhes: { linha: number; conteudo: string; erro: string }[] } | null>(null);
  const fileInputRef                        = useRef<HTMLInputElement>(null);

  const loadTests = () => {
    setLoading(true);
    api.get<NumberTest[]>('/number-tests')
      .then(r => setTests(r.data))
      .catch(err => {
        console.error('Erro ao carregar testes de conectividade:', err);
        setTests([]);
      })
      .finally(() => setLoading(false));
  };

  const buildResultParams = (
    p = 0,
    status = filterStatus,
    period = filterPeriod,
    from = dateFrom,
    to = dateTo,
    bu = filterBu,
    client = filterClient,
    operation = filterOperation,
    segment = filterSegment,
  ) => {
    const params = new URLSearchParams({ page: String(p), size: '30' });
    if (status)    params.set('status', status);
    if (bu)        params.set('businessUnitId', bu);
    if (client)    params.set('clientId', client);
    if (operation) params.set('operationId', operation);
    if (segment)   params.set('segmentId', segment);
    let fromVal = from, toVal = to;
    if (period !== 'custom') {
      const r = getPeriodRange(period as 'today' | 'week' | 'month');
      fromVal = r.from; toVal = r.to;
    }
    if (fromVal) params.set('dateFrom', fromVal);
    if (toVal)   params.set('dateTo', toVal);
    return params;
  };

  const loadResults = (
    p = 0,
    status = filterStatus,
    period = filterPeriod,
    from = dateFrom,
    to = dateTo,
    bu = filterBu,
    client = filterClient,
    operation = filterOperation,
    segment = filterSegment,
  ) => {
    setLoading(true);
    const params = buildResultParams(p, status, period, from, to, bu, client, operation, segment);
    api.get<PageResponse<TestResult>>(`/test-results?${params}`)
      .then(r => {
        setResults(r.data.content ?? []);
        setResTotalPages(r.data.totalPages);
        setResPage(r.data.number);
      })
      .catch(err => console.error('Erro ao carregar resultados de conectividade:', err))
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

  useEffect(() => {
    loadMasterData();
    if (tab === 'tests') loadTests();
    else if (tab === 'results') loadResults();
    // dashboard carrega internamente
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
    if (!form.phoneNumber?.trim()) { alert('Informe o número de telefone.'); return; }
    if (!form.businessUnit?.id) { alert('Selecione a Business Unit.'); return; }
    if (!form.client?.id) { alert('Selecione o Cliente.'); return; }
    if (!form.operation?.id) { alert('Selecione a Operação.'); return; }
    if (!form.segment?.id) { alert('Selecione o Segmento.'); return; }
    if (!form.startTime) { alert('Informe o horário inicial.'); return; }
    if (!form.intervalMinutes || form.intervalMinutes < 1) { alert('Intervalo deve ser de ao menos 1 minuto.'); return; }
    if (!form.quantity || form.quantity < 1) { alert('Quantidade deve ser de ao menos 1.'); return; }
    try {
      if (editId) { await api.put(`/number-tests/${editId}`, form); }
      else { await api.post('/number-tests', form); }
      setShowModal(false);
      loadTests();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao salvar o teste.'));
    }
  };


  const toggleActive = async (t: NumberTest) => {
    try {
      await api.patch(`/number-tests/${t.id}/active?active=${!t.isActive}`);
      loadTests();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao alterar status do teste.'));
    }
  };

  const deleteTest = async (id: number) => {
    if (!confirm('Remover este teste?')) return;
    try {
      await api.delete(`/number-tests/${id}`);
      loadTests();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao remover o teste.'));
    }
  };

  const handlePeriodFilter = (p: 'today' | 'week' | 'month') => {
    setFilterPeriod(p);
    setDateFrom(''); setDateTo('');
    loadResults(0, filterStatus, p, '', '', filterBu, filterClient, filterOperation, filterSegment);
  };

  const handleCustomFilter = () => {
    if (dateFrom && dateTo) {
      setFilterPeriod('custom');
      loadResults(0, filterStatus, 'custom', dateFrom, dateTo, filterBu, filterClient, filterOperation, filterSegment);
    }
  };

  const exportConnectivity = async () => {
    setExporting(true);
    try {
      const params = new URLSearchParams();
      if (filterStatus)    params.set('status', filterStatus);
      if (filterBu)        params.set('businessUnitId', filterBu);
      if (filterClient)    params.set('clientId', filterClient);
      if (filterOperation) params.set('operationId', filterOperation);
      if (filterSegment)   params.set('segmentId', filterSegment);
      let fromVal = dateFrom, toVal = dateTo;
      if (filterPeriod !== 'custom') {
        const r = getPeriodRange(filterPeriod as 'today' | 'week' | 'month');
        fromVal = r.from; toVal = r.to;
      }
      if (fromVal) params.set('dateFrom', fromVal + ':00');
      if (toVal)   params.set('dateTo', toVal + ':00');

      const response = await api.get(`/reports/connectivity?${params}`, {
        responseType: 'blob',
      });

      const url  = URL.createObjectURL(new Blob([response.data], { type: 'text/csv;charset=utf-8;' }));
      const link = document.createElement('a');
      link.href = url;
      const now = new Date().toISOString().slice(0, 10);
      link.setAttribute('download', `conectividade_${now}.csv`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      alert('Erro ao exportar. Tente novamente.');
    } finally {
      setExporting(false);
    }
  };


  // ─── Download do arquivo modelo ────────────────────────────────────────────
  const downloadTemplate = () => {
    const wb = XLSX.utils.book_new();

    // Aba 1: Modelo de importação
    const modelData = [
      ['numero', 'business_unit', 'cliente', 'operacao', 'segmento', 'horario_inicio', 'intervalo_minutos', 'quantidade', 'ativo'],
      ['+5511999990001', bus[0]?.name ?? 'Nome da BU', clients[0]?.name ?? 'Nome do Cliente', operations[0]?.name ?? 'Nome da Operação', segments[0]?.name ?? 'Nome do Segmento', '08:00', '60', '3', 'sim'],
      ['+5511999990002', bus[0]?.name ?? 'Nome da BU', clients[0]?.name ?? 'Nome do Cliente', operations[0]?.name ?? 'Nome da Operação', segments[0]?.name ?? 'Nome do Segmento', '09:00', '120', '5', 'sim'],
    ];
    const wsModel = XLSX.utils.aoa_to_sheet(modelData);
    wsModel['!cols'] = [18, 20, 20, 20, 20, 14, 18, 12, 8].map(w => ({ wch: w }));
    XLSX.utils.book_append_sheet(wb, wsModel, 'Importação');

    // Aba 2: Valores válidos de referência
    const maxRows = Math.max(bus.length, clients.length, operations.length, segments.length, 1);
    const refData = [['business_unit (valores válidos)', 'cliente (valores válidos)', 'operacao (valores válidos)', 'segmento (valores válidos)']];
    for (let i = 0; i < maxRows; i++) {
      refData.push([
        bus[i]?.name ?? '',
        clients[i]?.name ?? '',
        operations[i]?.name ?? '',
        segments[i]?.name ?? '',
      ]);
    }
    const wsRef = XLSX.utils.aoa_to_sheet(refData);
    wsRef['!cols'] = [30, 30, 30, 30].map(w => ({ wch: w }));
    XLSX.utils.book_append_sheet(wb, wsRef, 'Valores de Referência');

    XLSX.writeFile(wb, 'modelo_importacao_testes.xlsx');
  };

  // ─── Upload e importação ──────────────────────────────────────────────────
  const handleImport = async () => {
    if (!importFile) return;
    setImporting(true);
    setImportResult(null);
    const formData = new FormData();
    formData.append('file', importFile);
    try {
      const res = await api.post('/number-tests/import', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setImportResult(res.data);
      if (res.data.importados > 0) loadTests();
    } catch (err) {
      setImportResult({ importados: 0, erros: 1, detalhes: [{ linha: 0, conteudo: '', erro: getErrorMessage(err, 'Erro ao enviar arquivo.') }] });
    } finally {
      setImporting(false);
      setImportFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  return (
    <>
      <div className="page-header">
        <h1>📞 Testes de Conectividade</h1>
        <p>Gerenciamento de números a testar, histórico de resultados e dashboard de KPIs</p>
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
          <button className={`btn ${tab === 'dashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('dashboard')}>
            📈 Dashboard KPIs
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
              <div className="toolbar-right" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <button className="btn btn-primary" onClick={() => { setShowImport(true); setImportResult(null); }}>
                  📥 Importar Planilha
                </button>
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
                      <th>Próximo Teste</th>
                      <th>Status</th>
                      <th>Ações</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tests.length === 0 ? (
                      <tr><td colSpan={12} className="table-empty">
                        Nenhum teste cadastrado.<br />
                        <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                          Certifique-se de ter cadastrado BU, Cliente, Operação e Segmento em "Dados Mestres".
                        </span>
                      </td></tr>
                    ) : tests.map(t => (
                      <tr
                        key={t.id}
                        style={{ cursor: 'pointer' }}
                        onClick={e => {
                          // Clique fora dos botões → abre histórico
                          const target = e.target as HTMLElement;
                          if (!target.closest('button')) setHistTest(t);
                        }}
                        title="Clique para ver histórico de testes"
                      >
                        <td className="td-muted">{t.id}</td>
                        <td className="mono">{t.phoneNumber}</td>
                        <td>{t.businessUnit?.name}</td>
                        <td>{t.client?.name}</td>
                        <td>{t.operation?.name}</td>
                        <td>{t.segment?.name}</td>
                        <td className="td-muted">{t.startTime?.slice(0, 5)}</td>
                        <td className="td-muted">{t.intervalMinutes}min</td>
                        <td className="td-muted">{t.quantity}×</td>
                        <td className="td-muted" style={{ fontSize: '0.8rem' }}>
                          {t.isActive ? nextExecution(t.startTime, t.intervalMinutes) : '—'}
                        </td>
                        <td>
                          <span className={`badge ${t.isActive ? 'badge-success' : 'badge-gray'}`}>
                            {t.isActive ? 'Ativo' : 'Inativo'}
                          </span>
                        </td>
                        <td>
                          <div className="flex gap-1">
                            <button className="btn btn-ghost btn-sm btn-icon" onClick={e => { e.stopPropagation(); openEdit(t); }} title="Editar">✏️</button>
                            <button className="btn btn-ghost btn-sm btn-icon" onClick={e => { e.stopPropagation(); toggleActive(t); }} title={t.isActive ? 'Pausar' : 'Ativar'}>
                              {t.isActive ? '⏸' : '▶️'}
                            </button>
                            <button className="btn btn-danger btn-sm btn-icon" onClick={e => { e.stopPropagation(); deleteTest(t.id); }} title="Remover">🗑️</button>
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
            <div className="toolbar" style={{ flexWrap: 'wrap', gap: 8 }}>
              <div className="toolbar-left" style={{ flexWrap: 'wrap', gap: 8 }}>
                {/* Filtros rápidos de período */}
                <div className="flex gap-1">
                  {(['today', 'week', 'month'] as const).map(p => (
                    <button key={p}
                      className={`btn btn-sm ${filterPeriod === p ? 'btn-primary' : 'btn-ghost'}`}
                      onClick={() => handlePeriodFilter(p)}>
                      {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
                    </button>
                  ))}
                </div>
                {/* Range manual */}
                <div className="flex gap-1" style={{ alignItems: 'center' }}>
                  <input type="datetime-local" className="form-input" style={{ width: 170, fontSize: '0.8rem', padding: '4px 8px' }}
                    value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
                  <span style={{ color: 'var(--text-muted)' }}>→</span>
                  <input type="datetime-local" className="form-input" style={{ width: 170, fontSize: '0.8rem', padding: '4px 8px' }}
                    value={dateTo} onChange={e => setDateTo(e.target.value)} />
                  <button className="btn btn-ghost btn-sm" onClick={handleCustomFilter}>Filtrar</button>
                </div>
                {/* Filtro status */}
                <select className="form-select" style={{ width: 160 }} value={filterStatus}
                  onChange={e => { setFilterStatus(e.target.value); loadResults(0, e.target.value, filterPeriod, dateFrom, dateTo, filterBu, filterClient, filterOperation, filterSegment); }}>
                  <option value="">Todos os status</option>
                  {['SUCESSO', 'FALHA', 'OCUPADO', 'TIMEOUT', 'SEM_RESPOSTA', 'INVALIDO', 'INDISPONIVEL', 'RECUSADO'].map(s => (
                    <option key={s} value={s}>{s}</option>
                  ))}
                </select>
                {/* Filtro BU */}
                <select className="form-select" style={{ width: 150 }} value={filterBu}
                  onChange={e => { setFilterBu(e.target.value); loadResults(0, filterStatus, filterPeriod, dateFrom, dateTo, e.target.value, filterClient, filterOperation, filterSegment); }}>
                  <option value="">Todas as BUs</option>
                  {bus.map(b => <option key={b.id} value={String(b.id)}>{b.name}</option>)}
                </select>
                {/* Filtro Cliente */}
                <select className="form-select" style={{ width: 150 }} value={filterClient}
                  onChange={e => { setFilterClient(e.target.value); loadResults(0, filterStatus, filterPeriod, dateFrom, dateTo, filterBu, e.target.value, filterOperation, filterSegment); }}>
                  <option value="">Todos os clientes</option>
                  {clients.map(c => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
                </select>
                {/* Filtro Operação */}
                <select className="form-select" style={{ width: 150 }} value={filterOperation}
                  onChange={e => { setFilterOperation(e.target.value); loadResults(0, filterStatus, filterPeriod, dateFrom, dateTo, filterBu, filterClient, e.target.value, filterSegment); }}>
                  <option value="">Todas as operações</option>
                  {operations.map(o => <option key={o.id} value={String(o.id)}>{o.name}</option>)}
                </select>
                {/* Filtro Segmento */}
                <select className="form-select" style={{ width: 150 }} value={filterSegment}
                  onChange={e => { setFilterSegment(e.target.value); loadResults(0, filterStatus, filterPeriod, dateFrom, dateTo, filterBu, filterClient, filterOperation, e.target.value); }}>
                  <option value="">Todos os segmentos</option>
                  {segments.map(s => <option key={s.id} value={String(s.id)}>{s.name}</option>)}
                </select>
              </div>
              {/* Botão Exportar CSV */}
              <div className="toolbar-right" style={{ marginLeft: 'auto' }}>
                <button
                  id="btn-export-connectivity-csv"
                  className="btn btn-ghost btn-sm"
                  onClick={exportConnectivity}
                  disabled={exporting}
                  title="Exporta os resultados com os filtros aplicados"
                  style={{ borderColor: 'rgba(0,122,255,0.4)', color: '#4da8ff', minWidth: 140 }}
                >
                  {exporting
                    ? <><span className="spinner" style={{ width: 12, height: 12, margin: '0 6px 0 0' }} />Exportando…</>
                    : '⬇ Exportar CSV'}
                </button>
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
                      <th>Número</th>
                      <th>BU</th>
                      <th>Cliente</th>
                      <th>Operação</th>
                      <th>Segmento</th>
                      <th>Status</th>
                      <th>Código SIP</th>
                      <th>Descrição SIP</th>
                      <th>Ordem</th>
                    </tr>
                  </thead>
                  <tbody>
                    {results.length === 0 ? (
                      <tr><td colSpan={11} className="table-empty">Nenhum resultado neste período</td></tr>
                    ) : results.map(r => (
                      <tr key={r.id}>
                        <td className="td-muted">{r.id}</td>
                        <td className="td-muted">{formatDate(r.executedAt)}</td>
                        <td className="mono">{r.numberTest?.phoneNumber ?? '—'}</td>
                        <td>{r.numberTest?.businessUnit?.name ?? '—'}</td>
                        <td>{r.numberTest?.client?.name ?? '—'}</td>
                        <td>{r.numberTest?.operation?.name ?? '—'}</td>
                        <td>{r.numberTest?.segment?.name ?? '—'}</td>
                        <td><span className={`badge ${STATUS_CLASS[r.status] ?? 'badge-gray'}`}>{r.status}</span></td>
                        <td className="td-muted">{r.sipResponseCode ?? '—'}</td>
                        <td className="td-muted">{r.sipResponseReason || '—'}</td>
                        <td className="td-muted">{r.executionOrder}ª</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="pagination">
                  <span className="pagination-info">Página {resPage + 1} de {resTotalPages}</span>
                  <div className="pagination-btns">
                    <button className="page-btn" disabled={resPage === 0} onClick={() => loadResults(resPage - 1)}>‹</button>
                    <button className="page-btn" disabled={resPage >= resTotalPages - 1} onClick={() => loadResults(resPage + 1)}>›</button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {/* ---- DASHBOARD TAB ---- */}
        {tab === 'dashboard' && <DashboardKPIs />}
      </div>

      {/* Modal novo/editar teste */}
      {showModal && (
        <TestModal
          editId={editId} form={form} setForm={setForm}
          bus={bus} clients={clients} operations={operations} segments={segments}
          onClose={() => setShowModal(false)} onSave={save}
        />
      )}

      {/* Modal de importação */}
      {showImport && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) { setShowImport(false); setImportResult(null); } }}>
          <div className="modal" style={{ maxWidth: 600 }}>
            <div className="modal-header">
              <h2>📥 Importar Testes em Lote</h2>
              <button className="btn-close" onClick={() => { setShowImport(false); setImportResult(null); }}>×</button>
            </div>
            <div className="modal-body">

              {/* Instruções */}
              <div style={{
                background: 'rgba(0,122,255,0.08)', border: '1px solid rgba(0,122,255,0.2)',
                borderRadius: 10, padding: '12px 16px', marginBottom: 20, fontSize: '0.83rem',
                color: 'var(--text-muted)', lineHeight: 1.7,
              }}>
                <div style={{ fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 }}>📋 Instruções</div>
                <ul style={{ paddingLeft: 16, margin: 0 }}>
                  <li>Baixe o arquivo modelo e preencha a aba <strong>Importação</strong></li>
                  <li>Os nomes de BU, Cliente, Operação e Segmento devem ser <strong>exatamente</strong> como cadastrados</li>
                  <li>A aba <strong>Valores de Referência</strong> lista todos os nomes válidos</li>
                  <li>Horário no formato <strong>HH:mm</strong> (ex: <code>08:00</code>)</li>
                  <li>Campo <strong>ativo</strong>: <code>sim</code> ou <code>nao</code></li>
                  <li>Formatos aceitos: <strong>.xlsx</strong> e <strong>.csv</strong></li>
                </ul>
              </div>

              {/* Download do modelo + Upload */}
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginBottom: 20 }}>
                <button className="btn btn-ghost btn-sm"
                  onClick={downloadTemplate}
                  title="Baixar planilha modelo com os campos corretos e valores de referência"
                  style={{ borderColor: 'rgba(52,199,89,0.4)', color: '#34c759', whiteSpace: 'nowrap', flexShrink: 0 }}>
                  ⬇ Baixar Modelo .xlsx
                </button>

                <div style={{ flex: 1 }}>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".xlsx,.xls,.csv"
                    style={{ display: 'none' }}
                    onChange={e => { setImportFile(e.target.files?.[0] ?? null); setImportResult(null); }}
                  />
                  <div
                    onClick={() => fileInputRef.current?.click()}
                    style={{
                      border: `2px dashed ${importFile ? 'rgba(0,122,255,0.6)' : 'rgba(255,255,255,0.12)'}`,
                      borderRadius: 10, padding: '16px 20px', cursor: 'pointer',
                      textAlign: 'center', transition: 'all .2s',
                      background: importFile ? 'rgba(0,122,255,0.06)' : 'transparent',
                    }}
                    onDragOver={e => e.preventDefault()}
                    onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) { setImportFile(f); setImportResult(null); } }}
                  >
                    {importFile ? (
                      <div>
                        <div style={{ fontSize: '1.2rem', marginBottom: 4 }}>📄</div>
                        <div style={{ fontWeight: 500, fontSize: '0.88rem' }}>{importFile.name}</div>
                        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 2 }}>
                          {(importFile.size / 1024).toFixed(1)} KB · Clique para trocar
                        </div>
                      </div>
                    ) : (
                      <div>
                        <div style={{ fontSize: '1.5rem', marginBottom: 4 }}>📂</div>
                        <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                          Clique para selecionar ou arraste o arquivo aqui
                        </div>
                        <div style={{ fontSize: '0.75rem', color: 'rgba(148,163,184,0.5)', marginTop: 4 }}>
                          .xlsx · .xls · .csv
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* Resultado da importação */}
              {importResult && (
                <div style={{
                  background: importResult.erros === 0 ? 'rgba(52,199,89,0.08)' : 'rgba(255,159,10,0.08)',
                  border: `1px solid ${importResult.erros === 0 ? 'rgba(52,199,89,0.3)' : 'rgba(255,159,10,0.3)'}`,
                  borderRadius: 10, padding: '14px 18px', fontSize: '0.85rem',
                }}>
                  <div style={{ display: 'flex', gap: 20, marginBottom: importResult.detalhes.length > 0 ? 12 : 0 }}>
                    <div>
                      <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>Importados</div>
                      <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#34c759' }}>{importResult.importados}</div>
                    </div>
                    {importResult.erros > 0 && (
                      <div>
                        <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>Com erro</div>
                        <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#ff6b6b' }}>{importResult.erros}</div>
                      </div>
                    )}
                  </div>
                  {importResult.detalhes.length > 0 && (
                    <div style={{ maxHeight: 180, overflowY: 'auto' }}>
                      <div style={{ fontWeight: 500, marginBottom: 6, fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                        Detalhes dos erros:
                      </div>
                      {importResult.detalhes.map((d, i) => (
                        <div key={i} style={{
                          background: 'rgba(0,0,0,0.2)', borderRadius: 6, padding: '6px 10px',
                          marginBottom: 6, fontFamily: 'monospace', fontSize: '0.75rem',
                        }}>
                          <span style={{ color: '#ff6b6b' }}>Linha {d.linha}:</span>{' '}
                          <span style={{ color: '#ff9f0a' }}>{d.erro}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => { setShowImport(false); setImportResult(null); }}>
                {importResult ? 'Fechar' : 'Cancelar'}
              </button>
              {!importResult && (
                <button className="btn btn-primary" onClick={handleImport}
                  disabled={!importFile || importing}
                  style={{ minWidth: 130 }}>
                  {importing
                    ? <><span className="spinner" style={{ width: 12, height: 12, margin: '0 6px 0 0' }} />Importando…</>
                    : '📥 Importar'}
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Modal de histórico */}
      {histTest && <HistoricoModal test={histTest} onClose={() => setHistTest(null)} />}
    </>
  );
}
