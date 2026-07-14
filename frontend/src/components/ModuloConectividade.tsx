import { useEffect, useState, useCallback, useRef } from 'react';
import * as XLSX from 'xlsx';
import {
  BarChart, Bar, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
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

/** Calcula próxima execução do teste baseado em startTime + intervalMinutes. */
function nextExecution(startTime: string, intervalMinutes: number): string {
  const now = new Date();
  const [h, m] = startTime.split(':').map(Number);
  const base = new Date(now);
  base.setHours(h, m, 0, 0);
  if (base < now) base.setDate(base.getDate() + 1);

  // Ajusta para o próximo intervalo a partir de agora
  const msInterval = intervalMinutes * 60 * 1000;
  const diff = now.getTime() - base.getTime() + 24 * 60 * 60 * 1000;
  const intervals = Math.ceil(diff / msInterval);
  const next = new Date(base.getTime() + intervals * msInterval);
  if (next < now) return '—';

  const mins = Math.round((next.getTime() - now.getTime()) / 60000);
  if (mins < 60) return `em ${mins}min`;
  const hrs = Math.floor(mins / 60);
  const rem = mins % 60;
  return rem > 0 ? `em ${hrs}h ${rem}min` : `em ${hrs}h`;
}

// Períodos para filtros rápidos
function getPeriodRange(period: 'today' | 'week' | 'month'): { from: string; to: string } {
  const now = new Date();
  const to = now.toISOString().slice(0, 16);
  let from: Date;
  if (period === 'today') {
    from = new Date(now); from.setHours(0, 0, 0, 0);
  } else if (period === 'week') {
    from = new Date(now); from.setDate(now.getDate() - now.getDay() + 1); from.setHours(0, 0, 0, 0);
  } else {
    from = new Date(now.getFullYear(), now.getMonth(), 1);
  }
  return { from: from.toISOString().slice(0, 16), to };
}

const EMPTY_FORM: NumberTestCreate = {
  phoneNumber: '', businessUnit: { id: 0 }, client: { id: 0 },
  operation: { id: 0 }, segment: { id: 0 },
  startTime: '08:00:00', intervalMinutes: 60, quantity: 3, isActive: true,
};

// ─── Modal de histórico ───────────────────────────────────────────────────────

interface HistoricoModalProps {
  test: NumberTest;
  onClose: () => void;
}

function HistoricoModal({ test, onClose }: HistoricoModalProps) {
  const [results, setResults] = useState<TestResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [activePeriod, setActivePeriod] = useState<'today' | 'week' | 'month' | 'custom'>('today');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');

  const load = useCallback((p = 0, period: typeof activePeriod = activePeriod, from = dateFrom, to = dateTo) => {
    setLoading(true);
    let fromVal = from, toVal = to;
    if (period !== 'custom') {
      const r = getPeriodRange(period as 'today' | 'week' | 'month');
      fromVal = r.from; toVal = r.to;
    }
    const params = new URLSearchParams({ page: String(p), size: '20', numberTestId: String(test.id) });
    if (fromVal) params.set('dateFrom', fromVal);
    if (toVal) params.set('dateTo', toVal);
    api.get<PageResponse<TestResult>>(`/test-results?${params}`)
      .then(r => {
        setResults(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .catch(err => console.error('Erro ao carregar histórico de testes:', err))
      .finally(() => setLoading(false));
  }, [test.id, activePeriod, dateFrom, dateTo]);

  useEffect(() => { load(0, 'today'); }, []);

  const handlePeriod = (p: 'today' | 'week' | 'month') => {
    setActivePeriod(p);
    load(0, p, '', '');
  };

  const handleCustom = () => {
    if (dateFrom && dateTo) {
      setActivePeriod('custom');
      load(0, 'custom', dateFrom, dateTo);
    }
  };

  const successCount = results.filter(r => r.status === 'SUCESSO').length;
  const failCount = results.filter(r => r.status !== 'SUCESSO').length;

  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" style={{ maxWidth: 820, width: '95vw' }}>
        <div className="modal-header">
          <h2>📋 Histórico — {test.phoneNumber}</h2>
          <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
            {test.businessUnit?.name} › {test.client?.name} › {test.operation?.name} › {test.segment?.name}
          </div>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body" style={{ paddingTop: 8 }}>

          {/* Filtros de período */}
          <div className="flex gap-1" style={{ marginBottom: 12, flexWrap: 'wrap', alignItems: 'center' }}>
            {(['today', 'week', 'month'] as const).map(p => (
              <button
                key={p}
                className={`btn btn-sm ${activePeriod === p ? 'btn-primary' : 'btn-ghost'}`}
                onClick={() => handlePeriod(p)}
              >
                {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
              </button>
            ))}
            <div className="flex gap-1" style={{ alignItems: 'center', marginLeft: 8 }}>
              <input type="datetime-local" className="form-input" style={{ width: 170, fontSize: '0.8rem', padding: '4px 8px' }}
                value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
              <span style={{ color: 'var(--text-muted)' }}>→</span>
              <input type="datetime-local" className="form-input" style={{ width: 170, fontSize: '0.8rem', padding: '4px 8px' }}
                value={dateTo} onChange={e => setDateTo(e.target.value)} />
              <button className="btn btn-ghost btn-sm" onClick={handleCustom}>Filtrar</button>
            </div>
          </div>

          {/* Resumo */}
          {!loading && results.length > 0 && (
            <div className="flex gap-1" style={{ marginBottom: 12 }}>
              <div className="stat-card" style={{ flex: 1, padding: '10px 16px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Total</div>
                <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{results.length}</div>
              </div>
              <div className="stat-card" style={{ flex: 1, padding: '10px 16px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Sucesso</div>
                <div style={{ fontSize: '1.25rem', fontWeight: 700, color: '#34c759' }}>{successCount}</div>
              </div>
              <div className="stat-card" style={{ flex: 1, padding: '10px 16px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Falha</div>
                <div style={{ fontSize: '1.25rem', fontWeight: 700, color: '#ff6b6b' }}>{failCount}</div>
              </div>
              <div className="stat-card" style={{ flex: 1, padding: '10px 16px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Taxa sucesso</div>
                <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>
                  {results.length > 0 ? Math.round(successCount / results.length * 100) : 0}%
                </div>
              </div>
            </div>
          )}

          {/* Tabela */}
          {loading ? (
            <div className="loading-state"><div className="spinner" />Carregando histórico…</div>
          ) : (
            <div className="table-wrapper" style={{ maxHeight: 340 }}>
              <table>
                <thead>
                  <tr>
                    <th>Data/Hora</th>
                    <th>Status</th>
                    <th>Código SIP</th>
                    <th>Descrição SIP</th>
                    <th>Ordem</th>
                  </tr>
                </thead>
                <tbody>
                  {results.length === 0 ? (
                    <tr><td colSpan={5} className="table-empty">Nenhum resultado neste período</td></tr>
                  ) : results.map(r => (
                    <tr key={r.id}>
                      <td className="td-muted">{formatDate(r.executedAt)}</td>
                      <td><span className={`badge ${STATUS_CLASS[r.status] ?? 'badge-gray'}`}>{r.status}</span></td>
                      <td className="td-muted">{r.sipResponseCode ?? '—'}</td>
                      <td className="td-muted">{r.sipResponseReason || '—'}</td>
                      <td className="td-muted">{r.executionOrder}ª</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {totalPages > 1 && (
                <div className="pagination">
                  <span className="pagination-info">Página {page + 1} de {totalPages}</span>
                  <div className="pagination-btns">
                    <button className="page-btn" disabled={page === 0} onClick={() => load(page - 1)}>‹</button>
                    <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => load(page + 1)}>›</button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Dashboard KPIs Módulo 2 ──────────────────────────────────────────────────

interface ConnectivityStats {
  totalTestsToday: number;
  successesToday: number;
  failuresToday: number;
  totalTestsWeek: number;
  successesWeek: number;
  failuresWeek: number;
  successRatePct: number;
  failRatePct: number;
  completionRatePct: number;
  pendingPct: number;
  scheduledCount: number;
}

function DashboardKPIs() {
  const [stats, setStats] = useState<ConnectivityStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState<'today' | 'week' | 'month'>('today');

  const load = (p: 'today' | 'week' | 'month') => {
    setLoading(true);
    api.get<ConnectivityStats>(`/stats/connectivity?period=${p}`)
      .then(r => setStats(r.data))
      .catch(err => console.error('Erro ao carregar KPIs de conectividade:', err))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load('today'); }, []);

  const handlePeriod = (p: typeof period) => { setPeriod(p); load(p); };

  if (loading) return <div className="loading-state"><div className="spinner" />Carregando KPIs…</div>;
  if (!stats) return null;

  const total = period === 'today' ? stats.totalTestsToday : stats.totalTestsWeek;
  const success = period === 'today' ? stats.successesToday : stats.successesWeek;
  const failures = period === 'today' ? stats.failuresToday : stats.failuresWeek;
  const pieData = [
    { name: 'Sucesso', value: success },
    { name: 'Falha/Outro', value: Math.max(0, total - success) },
  ];
  const barData = [
    { name: 'Realizados', value: total },
    { name: 'Agendados', value: stats.scheduledCount },
  ];

  return (
    <div>
      {/* Filtros */}
      <div className="flex gap-1" style={{ marginBottom: 20 }}>
        {(['today', 'week', 'month'] as const).map(p => (
          <button key={p} className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => handlePeriod(p)}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
          </button>
        ))}
      </div>

      {/* KPI cards — os 7 solicitados */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(170px, 1fr))', gap: 12, marginBottom: 24 }}>
        {[
          { label: 'Testes Realizados', value: total, color: '#007aff' },
          { label: 'Testes Agendados', value: stats.scheduledCount, color: '#3b82f6' },
          { label: 'Sucessos', value: success, color: '#34c759' },
          { label: 'Falhas', value: failures, color: '#ff6b6b' },
          { label: 'Taxa de Sucesso', value: `${stats.successRatePct}%`, color: '#34c759' },
          { label: 'Taxa de Falha', value: `${stats.failRatePct}%`, color: '#ff6b6b' },
          { label: '% Realizado', value: `${stats.completionRatePct}%`, color: '#ff9f0a' },
        ].map(kpi => (
          <div key={kpi.label} className="stat-card" style={{ padding: '16px 20px' }}>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 4 }}>{kpi.label}</div>
            <div style={{ fontSize: '1.6rem', fontWeight: 700, color: kpi.color }}>{kpi.value}</div>
          </div>
        ))}
      </div>

      {/* Gráficos */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        <div className="stat-card" style={{ padding: 16 }}>
          <h3 style={{ fontSize: '0.9rem', marginBottom: 12, color: 'var(--text-muted)' }}>Sucesso × Falha</h3>
          <ResponsiveContainer width="100%" height={200}>
            <PieChart>
              <Pie data={pieData} cx="50%" cy="50%" outerRadius={70} dataKey="value" label={({ name, percent }) => percent != null ? `${name} ${(percent * 100).toFixed(0)}%` : name}>
                <Cell fill="#34c759" />
                <Cell fill="#ff6b6b" />
              </Pie>
              <Tooltip />
            </PieChart>
          </ResponsiveContainer>
        </div>
        <div className="stat-card" style={{ padding: 16 }}>
          <h3 style={{ fontSize: '0.9rem', marginBottom: 12, color: 'var(--text-muted)' }}>Realizados × Agendados</h3>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={barData}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
              <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid rgba(255,255,255,0.1)' }} />
              <Bar dataKey="value" fill="#007aff" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
}

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
    } catch (err: any) {
      alert(err?.response?.data?.message ?? 'Erro ao salvar o teste.');
    }
  };


  const toggleActive = async (t: NumberTest) => {
    try {
      await api.patch(`/number-tests/${t.id}/active?active=${!t.isActive}`);
      loadTests();
    } catch (err: any) {
      alert(err?.response?.data?.message ?? 'Erro ao alterar status do teste.');
    }
  };

  const deleteTest = async (id: number) => {
    if (!confirm('Remover este teste?')) return;
    try {
      await api.delete(`/number-tests/${id}`);
      loadTests();
    } catch (err: any) {
      alert(err?.response?.data?.message ?? 'Erro ao remover o teste.');
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
    } catch (err: any) {
      setImportResult({ importados: 0, erros: 1, detalhes: [{ linha: 0, conteudo: '', erro: err.response?.data?.error ?? 'Erro ao enviar arquivo.' }] });
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
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowModal(false); }}>
          <div className="modal modal-lg">
            <div className="modal-header">
              <h2>📞 {editId ? 'Editar' : 'Novo'} Teste de Conectividade</h2>
              <button className="btn-close" onClick={() => setShowModal(false)}>×</button>
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
              <button className="btn btn-ghost" onClick={() => setShowModal(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save}>{editId ? 'Salvar Alterações' : 'Criar Teste'}</button>
            </div>
          </div>
        </div>
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
