import { useEffect, useState } from 'react';
import api from '../api/client';

// ─── Types ────────────────────────────────────────────────────────────────────

interface AuditLog {
  id: number;
  createdAt: string;
  username: string | null;
  ipAddress: string | null;
  action: string;
  details: string | null;
  success: boolean;
  userAgent: string | null;
}

interface PageResponse<T> {
  content: T[];
  totalPages: number;
  number: number;
  totalElements: number;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

const ACTION_BADGE: Record<string, string> = {
  LOGIN:               'badge-success',
  LOGIN_FAILED:        'badge-danger',
  SETTINGS_CHANGE:     'badge-warning',
  USER_CREATE:         'badge-info',
  USER_UPDATE:         'badge-info',
  USER_DELETE:         'badge-danger',
  EXPORT:              'badge-gray',
  RATE_LIMIT_BLOCKED:  'badge-danger',
  TOTP_ENABLED:        'badge-success',
  TOTP_DISABLED:       'badge-warning',
  TOTP_VERIFY_FAILED:  'badge-danger',
};

const ACTION_LABEL: Record<string, string> = {
  LOGIN:               'Login',
  LOGIN_FAILED:        'Falha Login',
  SETTINGS_CHANGE:     'Config Alterada',
  USER_CREATE:         'Usuário Criado',
  USER_UPDATE:         'Usuário Editado',
  USER_DELETE:         'Usuário Removido',
  EXPORT:              'Exportação',
  RATE_LIMIT_BLOCKED:  'Rate Limit',
  TOTP_ENABLED:        '2FA Ativado',
  TOTP_DISABLED:       '2FA Desativado',
  TOTP_VERIFY_FAILED:  'Falha 2FA',
};

function formatDate(s: string) {
  const d = new Date(s);
  return d.toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

// ─── Componente Principal ─────────────────────────────────────────────────────

export default function Auditoria() {
  const [logs, setLogs]           = useState<AuditLog[]>([]);
  const [loading, setLoading]     = useState(true);
  const [page, setPage]           = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  // Filtros
  const [filterUsername, setFilterUsername] = useState('');
  const [filterAction, setFilterAction]     = useState('');
  const [filterFrom, setFilterFrom]         = useState('');
  const [filterTo, setFilterTo]             = useState('');
  const [actions, setActions]               = useState<string[]>([]);

  useEffect(() => {
    api.get<string[]>('/audit/actions').then(r => setActions(r.data))
      .catch(err => console.error('Erro ao carregar ações de auditoria:', err));
  }, []);

  const load = (p = 0, username = filterUsername, action = filterAction, from = filterFrom, to = filterTo) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '50' });
    if (username) params.set('username', username);
    if (action)   params.set('action', action);
    if (from)     params.set('dateFrom', from + ':00');
    if (to)       params.set('dateTo', to + ':00');
    api.get<PageResponse<AuditLog>>(`/audit?${params}`)
      .then(r => {
        setLogs(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
        setTotalElements(r.data.totalElements);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const handleFilter = () => load(0);

  const clearFilters = () => {
    setFilterUsername(''); setFilterAction('');
    setFilterFrom('');     setFilterTo('');
    load(0, '', '', '', '');
  };

  return (
    <>
      <div className="page-header">
        <h1>🔐 Auditoria de Segurança</h1>
        <p>Registro de todas as ações realizadas no sistema — logins, configurações, exportações e erros de autenticação</p>
      </div>

      <div className="page-body">
        {/* KPIs */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
          {[
            { label: 'Total de eventos', value: totalElements, color: 'var(--text-primary)' },
            { label: 'Página atual', value: `${page + 1} / ${totalPages}`, color: 'var(--text-muted)' },
            { label: 'Itens por página', value: 50, color: 'var(--text-muted)' },
            { label: 'Filtros ativos', value: [filterUsername, filterAction, filterFrom].filter(Boolean).length, color: '#4da8ff' },
          ].map(k => (
            <div key={k.label} className="stat-card" style={{ padding: '12px 16px' }}>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>{k.label}</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: k.color }}>{k.value}</div>
            </div>
          ))}
        </div>

        {/* Filtros */}
        <div className="toolbar" style={{ flexWrap: 'wrap', gap: 8, marginBottom: 16 }}>
          <div className="toolbar-left" style={{ flexWrap: 'wrap', gap: 8 }}>
            <input
              type="text"
              className="form-input"
              aria-label="Filtrar por usuário"
              placeholder="Filtrar por usuário…"
              style={{ width: 180 }}
              value={filterUsername}
              onChange={e => setFilterUsername(e.target.value)}
            />
            <select
              className="form-select"
              style={{ width: 180 }}
              value={filterAction}
              onChange={e => setFilterAction(e.target.value)}
            >
              <option value="">Todas as ações</option>
              {actions.map(a => (
                <option key={a} value={a}>{ACTION_LABEL[a] ?? a}</option>
              ))}
            </select>
            <input
              type="datetime-local"
              className="form-input"
              style={{ width: 175, fontSize: '0.8rem' }}
              value={filterFrom}
              onChange={e => setFilterFrom(e.target.value)}
            />
            <span style={{ color: 'var(--text-muted)', alignSelf: 'center' }}>→</span>
            <input
              type="datetime-local"
              className="form-input"
              style={{ width: 175, fontSize: '0.8rem' }}
              value={filterTo}
              onChange={e => setFilterTo(e.target.value)}
            />
            <button className="btn btn-primary btn-sm" onClick={handleFilter}>Filtrar</button>
            <button className="btn btn-ghost btn-sm" onClick={clearFilters}>Limpar</button>
          </div>
          <div className="toolbar-right" style={{ marginLeft: 'auto' }}>
            <button
              className="btn btn-ghost btn-sm"
              style={{ borderColor: 'rgba(0,122,255,0.4)', color: '#4da8ff' }}
              onClick={() => load(page)}
              title="Atualizar"
            >
              🔄 Atualizar
            </button>
          </div>
        </div>

        {/* Tabela */}
        {loading ? (
          <div className="loading-state"><div className="spinner" />Carregando auditoria…</div>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th style={{ width: 160 }}>Data / Hora</th>
                  <th style={{ width: 120 }}>Usuário</th>
                  <th style={{ width: 140 }}>IP</th>
                  <th style={{ width: 150 }}>Ação</th>
                  <th>Detalhes</th>
                  <th style={{ width: 70 }}>Status</th>
                </tr>
              </thead>
              <tbody>
                {logs.length === 0 ? (
                  <tr><td colSpan={6} className="table-empty">Nenhum evento de auditoria encontrado</td></tr>
                ) : logs.map(log => (
                  <tr key={log.id}>
                    <td className="td-muted" style={{ fontSize: '0.78rem' }}>{formatDate(log.createdAt)}</td>
                    <td className="mono">{log.username ?? <span className="td-muted">—</span>}</td>
                    <td className="mono td-muted" style={{ fontSize: '0.78rem' }}>{log.ipAddress ?? '—'}</td>
                    <td>
                      <span className={`badge ${ACTION_BADGE[log.action] ?? 'badge-gray'}`} style={{ fontSize: '0.7rem' }}>
                        {ACTION_LABEL[log.action] ?? log.action}
                      </span>
                    </td>
                    <td style={{ maxWidth: 400, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: '0.82rem' }}
                        title={log.details ?? undefined}>
                      {log.details ?? <span className="td-muted">—</span>}
                    </td>
                    <td>
                      {log.success
                        ? <span className="badge badge-success" style={{ fontSize: '0.7rem' }}>✓ OK</span>
                        : <span className="badge badge-danger"  style={{ fontSize: '0.7rem' }}>✗ Falha</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* Paginação */}
            <div className="pagination">
              <span className="pagination-info">Página {page + 1} de {totalPages} ({totalElements} eventos)</span>
              <button className="btn btn-ghost btn-sm" disabled={page === 0} onClick={() => load(page - 1)}>← Anterior</button>
              <button className="btn btn-ghost btn-sm" disabled={page >= totalPages - 1} onClick={() => load(page + 1)}>Próximo →</button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
