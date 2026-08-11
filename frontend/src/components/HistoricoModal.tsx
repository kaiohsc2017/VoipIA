import { useEffect, useState, useCallback } from 'react';
import api from '../api/client';
import type { NumberTest, TestResult, PageResponse } from '../api/types';
import { STATUS_CLASS, formatDate, getPeriodRange } from './connectivityHelpers';

interface HistoricoModalProps {
  test: NumberTest;
  onClose: () => void;
}

export function HistoricoModal({ test, onClose }: HistoricoModalProps) {
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
