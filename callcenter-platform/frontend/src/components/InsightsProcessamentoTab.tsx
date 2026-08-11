import { Fragment, useEffect, useState } from 'react';
import api from '../api/client';
import type { CcInsightProcessingItem, CcInsightsDrillDownFilters, Page } from '../api/types';

function formatDate(iso?: string) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

const STATUS_LABEL: Record<string, string> = {
  pending: 'Pendente',
  processing: 'Processando',
  done: 'Concluído',
  error: 'Erro',
};

const STATUS_COLOR: Record<string, string> = {
  pending: '#94a3b8',
  processing: '#ff9f0a',
  done: '#34c759',
  error: '#ff3b30',
};

function StatusBadge({ status }: { status: string }) {
  const color = STATUS_COLOR[status] ?? '#94a3b8';
  return (
    <span style={{
      display: 'inline-block', padding: '2px 8px', borderRadius: 12, fontSize: '.75rem',
      fontWeight: 600, color, background: `${color}22`, border: `1px solid ${color}55`,
    }}>
      {STATUS_LABEL[status] ?? status}
    </span>
  );
}

/**
 * InsightsProcessamentoTab (Call Center) — status/fila de cada gravação de fila desde o
 * registro (feito pelo Java em CallCenterRecordingService.registerInsights, ver Fase 8) até
 * concluir ou falhar. Adaptado de insights-platform/frontend/src/components/
 * InsightsProcessingTab.tsx — mesma estrutura, sem o filtro por nome de arquivo (o Call
 * Center registra por callRef "cc-<channelUniqueId>", não por nome de .wav descoberto em disco).
 */
export function InsightsProcessamentoTab({ onDrillDown }: { onDrillDown: (filters: CcInsightsDrillDownFilters) => void }) {
  const [items, setItems] = useState<CcInsightProcessingItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);

  const [filtersOpen, setFiltersOpen] = useState(false);
  const [statusFilter, setStatusFilter] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [expandedError, setExpandedError] = useState<number | null>(null);

  const hasActiveFilters = !!(statusFilter || dateFrom || dateTo);

  const loadItems = (p = 0, filters = { statusFilter, dateFrom, dateTo }) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '20' });
    if (filters.statusFilter) params.set('status', filters.statusFilter);
    if (filters.dateFrom) params.set('dateFrom', filters.dateFrom);
    if (filters.dateTo) params.set('dateTo', filters.dateTo);
    api.get<Page<CcInsightProcessingItem>>(`/callcenter/insights/processing?${params}`)
      .then(r => {
        setItems(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .catch(err => {
        console.error('Erro ao carregar fila de processamento do Insights do Call Center:', err);
        setItems([]);
      })
      .finally(() => setLoading(false));
  };

  // Filtros só recarregam via botão "Aplicar filtros"/clearFilters, não a cada digitação — mount-only intencional.
  useEffect(() => { loadItems(0); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const clearFilters = () => {
    setStatusFilter(''); setDateFrom(''); setDateTo('');
    loadItems(0, { statusFilter: '', dateFrom: '', dateTo: '' });
  };

  return (
    <>
      <div className="page-header">
        <div><h1>Insights — Processamento</h1><p>Fila de análise de IA das gravações do Call Center</p></div>
      </div>

      <div className="toolbar">
        <div className="toolbar-left">
          <button
            type="button"
            className={`btn btn-sm ${hasActiveFilters ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setFiltersOpen(o => !o)}
          >
            🔧 Filtros{hasActiveFilters ? ' •' : ''}
          </button>
        </div>
      </div>

      {filtersOpen && (
        <div className="form-grid" style={{ gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 20 }}>
          <div>
            <label className="form-label">Status</label>
            <select className="form-select" value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
              <option value="">Todos</option>
              <option value="pending">Pendente</option>
              <option value="processing">Processando</option>
              <option value="done">Concluído</option>
              <option value="error">Erro</option>
            </select>
          </div>
          <div>
            <label className="form-label">Data de</label>
            <input type="date" className="form-input" value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Data até</label>
            <input type="date" className="form-input" value={dateTo} onChange={e => setDateTo(e.target.value)} />
          </div>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8 }}>
            <button className="btn btn-primary btn-sm" onClick={() => loadItems(0)}>Aplicar filtros</button>
            <button className="btn btn-ghost btn-sm" onClick={clearFilters} disabled={!hasActiveFilters}>Limpar</button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando fila de processamento…</div>
      ) : (
        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Gravação</th>
                <th>Data início</th>
                <th>Data fim</th>
                <th>Posição na fila</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {items.length === 0 ? (
                <tr><td colSpan={5} className="table-empty">Nenhuma gravação encontrada</td></tr>
              ) : items.map(item => (
                <Fragment key={item.id}>
                  <tr
                    style={item.status === 'done' || item.status === 'error' ? { cursor: 'pointer' } : undefined}
                    onClick={() => {
                      if (item.status === 'done') onDrillDown({ id: item.id });
                      else if (item.status === 'error') setExpandedError(expandedError === item.id ? null : item.id);
                    }}
                  >
                    <td className="mono">{item.fileName}</td>
                    <td className="td-muted">{formatDate(item.ingestedAt)}</td>
                    <td className="td-muted">{formatDate(item.processedAt)}</td>
                    <td className="td-muted">{item.queuePosition != null ? `${item.queuePosition}º` : '—'}</td>
                    <td><StatusBadge status={item.status} /></td>
                  </tr>
                  {expandedError === item.id && item.errorMsg && (
                    <tr>
                      <td colSpan={5} style={{ background: 'rgba(255,59,48,0.08)', padding: '8px 16px', fontSize: '.8rem', color: '#ff3b30' }}>
                        {item.errorMsg}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
          <div className="pagination">
            <span className="pagination-info">{items.length} registros nesta página</span>
            <div className="pagination-btns">
              <button className="page-btn" disabled={page === 0} onClick={() => loadItems(page - 1)}>‹</button>
              {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => (
                <button key={i} className={`page-btn ${i === page ? 'active' : ''}`} onClick={() => loadItems(i)}>
                  {i + 1}
                </button>
              ))}
              <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => loadItems(page + 1)}>›</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
