import { useEffect, useState } from 'react';
import api from '../api/client';
import type { CallCostView, PageResponse, Ura } from '../api/types';

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

function formatUsd(value: number) {
  return `US$ ${value.toFixed(4)}`;
}

function formatTokens(value: number) {
  return value.toLocaleString('pt-BR');
}

interface CostsTabProps {
  uras: Ura[];
  /** Preenchidos pelo drill-down do Dashboard de Custos (mês clicado). */
  initialDateFrom?: string;
  initialDateTo?: string;
  /** Avisa o pai (ModuloURA) que o drill-down já foi aplicado, para não "grudar" numa
   * troca de aba manual seguinte (o pai zera o range guardado). */
  onInitialFiltersConsumed?: () => void;
}

/** Aba "Custos IA" — lista paginada de chamadas com tokens consumidos e custo estimado,
 * no mesmo padrão de filtros/paginação da aba Chamadas (ModuloURA). */
export function CostsTab({ uras, initialDateFrom, initialDateTo, onInitialFiltersConsumed }: CostsTabProps) {
  const [costs, setCosts] = useState<CallCostView[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);

  const [filtersOpen, setFiltersOpen] = useState(!!(initialDateFrom || initialDateTo));
  const [uraFilter, setUraFilter] = useState('');
  const [clientNameFilter, setClientNameFilter] = useState('');
  const [dateFrom, setDateFrom] = useState(initialDateFrom ?? '');
  const [dateTo, setDateTo] = useState(initialDateTo ?? '');

  const hasActiveFilters = !!(uraFilter || clientNameFilter || dateFrom || dateTo);

  const loadCosts = (p = 0, filters = { uraFilter, clientNameFilter, dateFrom, dateTo }) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '20' });
    if (filters.uraFilter) params.set('uraId', filters.uraFilter);
    if (filters.clientNameFilter) params.set('clientName', filters.clientNameFilter);
    if (filters.dateFrom) params.set('dateFrom', filters.dateFrom);
    if (filters.dateTo) params.set('dateTo', filters.dateTo);
    api.get<PageResponse<CallCostView>>(`/calls/costs?${params}`)
      .then(r => {
        setCosts(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .catch(err => {
        console.error('Erro ao carregar custos de IA:', err);
        setCosts([]);
      })
      .finally(() => setLoading(false));
  };

  // Filtros só recarregam via botão "Aplicar filtros"/clearFilters, não a cada digitação — mount-only
  // intencional. Usa os valores iniciais (drill-down do Dashboard de Custos) já no primeiro load e
  // avisa o pai que o drill-down foi consumido, para não reaplicar numa troca de aba manual futura.
  useEffect(() => {
    loadCosts(0, { uraFilter, clientNameFilter, dateFrom, dateTo });
    onInitialFiltersConsumed?.();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const clearFilters = () => {
    setUraFilter(''); setClientNameFilter(''); setDateFrom(''); setDateTo('');
    loadCosts(0, { uraFilter: '', clientNameFilter: '', dateFrom: '', dateTo: '' });
  };

  const totalCostThisPage = costs.reduce((sum, c) => sum + c.estimatedCostUsd, 0);

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
        <div className="toolbar-right">
          <span style={{ fontSize: '.8rem', color: 'var(--text-muted)' }}>
            Custo nesta página: <strong>{formatUsd(totalCostThisPage)}</strong>
          </span>
        </div>
      </div>

      {filtersOpen && (
        <div className="form-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
          <div>
            <label className="form-label">URA</label>
            <select className="form-select" value={uraFilter} onChange={e => setUraFilter(e.target.value)}>
              <option value="">Todas as URAs</option>
              {uras.map(u => (
                <option key={u.id} value={u.id}>{u.name} (ramal {u.extension})</option>
              ))}
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
          <div>
            <label className="form-label">Cliente</label>
            <input className="form-input" placeholder="ex: kaio.correa" value={clientNameFilter} onChange={e => setClientNameFilter(e.target.value)} />
          </div>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8 }}>
            <button className="btn btn-primary btn-sm" onClick={() => loadCosts(0)}>Aplicar filtros</button>
            <button className="btn btn-ghost btn-sm" onClick={clearFilters} disabled={!hasActiveFilters}>Limpar</button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando custos…</div>
      ) : (
        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Data / Hora</th>
                <th>Cliente</th>
                <th>Duração</th>
                <th>Tokens STT</th>
                <th>Tokens LLM</th>
                <th>Tokens TTS</th>
                <th>Total tokens</th>
                <th>Custo estimado</th>
              </tr>
            </thead>
            <tbody>
              {costs.length === 0 ? (
                <tr><td colSpan={9} className="table-empty">Nenhuma chamada com custo de IA registrado</td></tr>
              ) : costs.map(c => (
                <tr key={c.id}>
                  <td className="td-muted">{c.id}</td>
                  <td className="td-muted">{formatDate(c.callDate)}</td>
                  <td>{c.clientName || <span className="text-muted">—</span>}</td>
                  <td className="td-muted">{c.callDurationSecs}s</td>
                  <td className="mono">{formatTokens(c.sttTokensIn + c.sttTokensOut)}</td>
                  <td className="mono">{formatTokens(c.llmTokensIn + c.llmTokensOut)}</td>
                  <td className="mono">{formatTokens(c.ttsTokensIn + c.ttsTokensOut)}</td>
                  <td className="mono">{formatTokens(c.totalTokens)}</td>
                  <td className="mono" style={{ fontWeight: 600 }}>{formatUsd(c.estimatedCostUsd)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pagination">
            <span className="pagination-info">{costs.length} registros nesta página</span>
            <div className="pagination-btns">
              <button className="page-btn" disabled={page === 0} onClick={() => loadCosts(page - 1)}>‹</button>
              {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => (
                <button key={i} className={`page-btn ${i === page ? 'active' : ''}`} onClick={() => loadCosts(i)}>
                  {i + 1}
                </button>
              ))}
              <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => loadCosts(page + 1)}>›</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
