import { Fragment, useEffect, useState } from 'react';
import api from '../api/client';
import type { InsightProcessingItem, PageResponse } from '../api/types';

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

/** Aba "Processamento" — status/fila de cada arquivo .wav/.xml descoberto em /opt/audio,
 * desde a descoberta até concluir ou falhar. Sem mirror direto (tabela nova) — estrutura de
 * busca/filtro segue o mesmo padrão das demais abas de Insights. */
export function InsightsProcessingTab() {
  const [items, setItems] = useState<InsightProcessingItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);

  const [filtersOpen, setFiltersOpen] = useState(false);
  const [statusFilter, setStatusFilter] = useState('');
  const [fileNameFilter, setFileNameFilter] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [expandedError, setExpandedError] = useState<number | null>(null);

  const hasActiveFilters = !!(statusFilter || fileNameFilter || dateFrom || dateTo);

  const loadItems = (p = 0, filters = { statusFilter, fileNameFilter, dateFrom, dateTo }) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '20' });
    if (filters.statusFilter) params.set('status', filters.statusFilter);
    if (filters.fileNameFilter) params.set('fileName', filters.fileNameFilter);
    if (filters.dateFrom) params.set('dateFrom', filters.dateFrom);
    if (filters.dateTo) params.set('dateTo', filters.dateTo);
    api.get<PageResponse<InsightProcessingItem>>(`/insights/processing?${params}`)
      .then(r => {
        setItems(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .catch(err => {
        console.error('Erro ao carregar fila de processamento de Insights:', err);
        setItems([]);
      })
      .finally(() => setLoading(false));
  };

  // Filtros só recarregam via botão "Aplicar filtros"/clearFilters, não a cada digitação — mount-only intencional.
  useEffect(() => { loadItems(0); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const clearFilters = () => {
    setStatusFilter(''); setFileNameFilter(''); setDateFrom(''); setDateTo('');
    loadItems(0, { statusFilter: '', fileNameFilter: '', dateFrom: '', dateTo: '' });
  };

  return (
    <>
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
        <div className="form-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
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
            <label className="form-label">Nome do arquivo</label>
            <input className="form-input" placeholder="ex: 20260717-..." value={fileNameFilter} onChange={e => setFileNameFilter(e.target.value)} />
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
                <th>Arquivo</th>
                <th>Data início</th>
                <th>Data fim</th>
                <th>Posição na fila</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {items.length === 0 ? (
                <tr><td colSpan={5} className="table-empty">Nenhum arquivo encontrado</td></tr>
              ) : items.map(item => (
                <Fragment key={item.id}>
                  <tr
                    key={item.id}
                    style={item.status === 'error' ? { cursor: 'pointer' } : undefined}
                    onClick={() => item.status === 'error' && setExpandedError(expandedError === item.id ? null : item.id)}
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
