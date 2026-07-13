import { useEffect, useState, useCallback, useRef } from 'react';
import { subscribe } from '../api/websocket';
import api from '../api/client';
import type { CallRecord, PageResponse, Ura } from '../api/types';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import UraManagementTab from './UraManagementTab';
import { AuthedAudio } from './AuthedAudio';

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

// ─── Dashboard com gráfico temporal ─────────────────────────────────────────

interface TimePoint { date: string; total: number; jiraOpened: number; avgDuration: number; }

interface DashboardQuery { period: 'week' | 'month'; dateFrom: string; dateTo: string; }

function DashboardTab({ onDrillDown }: { onDrillDown: (filters: RankingDrillDownFilters) => void }) {
  const [series, setSeries] = useState<TimePoint[]>([]);
  const [period, setPeriod] = useState<'week' | 'month'>('week');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [loading, setLoading] = useState(true);

  const hasCustomRange = !!(customFrom && customTo);

  const load = useCallback((q: DashboardQuery) => {
    setLoading(true);
    const params = (q.dateFrom && q.dateTo)
      ? new URLSearchParams({ dateFrom: q.dateFrom, dateTo: q.dateTo })
      : new URLSearchParams({ period: q.period });
    api.get<TimePoint[]>(`/stats/calls/timeseries?${params}`)
      .then(r => setSeries(r.data))
      .finally(() => setLoading(false));
  }, []);

  // Ref sempre atualizado com a consulta atual — evita stale closure no callback do WebSocket
  const queryRef = useRef<DashboardQuery>({ period, dateFrom: customFrom, dateTo: customTo });
  useEffect(() => { queryRef.current = { period, dateFrom: customFrom, dateTo: customTo }; }, [period, customFrom, customTo]);

  // Carrega na montagem e se inscreve no WebSocket para atualizar em tempo real
  useEffect(() => {
    load({ period: 'week', dateFrom: '', dateTo: '' });
    const unsub = subscribe('/topic/calls', () => {
      // Nova chamada registrada — recarrega o gráfico sem trocar o filtro atual
      load(queryRef.current);
    });
    return () => unsub?.();
  }, []);  // eslint-disable-line react-hooks/exhaustive-deps

  const selectPeriod = (p: 'week' | 'month') => {
    setPeriod(p); setCustomFrom(''); setCustomTo('');
    load({ period: p, dateFrom: '', dateTo: '' });
  };
  const applyCustomRange = () => {
    if (!customFrom || !customTo) return;
    load({ period, dateFrom: customFrom, dateTo: customTo });
  };

  const formatDateLocal = (d: string) => {
    if (!d) return '';
    const dt = new Date(d);
    return `${String(dt.getDate()).padStart(2,'0')}/${String(dt.getMonth()+1).padStart(2,'0')}`;
  };

  const chartData = series.map(p => ({
    ...p,
    rawDate: p.date,
    date: formatDateLocal(p.date),
    Chamadas: p.total,
    'Jira Abertas': p.jiraOpened,
  }));

  const drillDownToDay = (entry: unknown) => {
    const item = entry as { rawDate?: string; payload?: { rawDate?: string } };
    const rawDate = item.payload?.rawDate ?? item.rawDate;
    if (rawDate) onDrillDown({ dateFrom: rawDate, dateTo: rawDate });
  };

  return (
    <div>
      <div className="flex gap-1" style={{ marginBottom: 16, flexWrap: 'wrap', alignItems: 'center' }}>
        {(['week', 'month'] as const).map(p => (
          <button key={p}
            className={`btn btn-sm ${period === p && !hasCustomRange ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => selectPeriod(p)}>
            {p === 'week' ? 'Últimos 7 dias' : 'Últimos 30 dias'}
          </button>
        ))}
        <span style={{ color: 'var(--text-muted)', fontSize: '.8rem', margin: '0 4px' }}>ou período customizado:</span>
        <input type="date" className="form-input" style={{ maxWidth: 150 }} value={customFrom}
          onChange={e => setCustomFrom(e.target.value)} />
        <input type="date" className="form-input" style={{ maxWidth: 150 }} value={customTo}
          onChange={e => setCustomTo(e.target.value)} />
        <button className={`btn btn-sm ${hasCustomRange ? 'btn-primary' : 'btn-ghost'}`}
          onClick={applyCustomRange} disabled={!customFrom || !customTo}>
          Aplicar
        </button>
      </div>
      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando gráfico…</div>
      ) : series.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
          Sem chamadas no período selecionado
        </div>
      ) : (
        <div className="stat-card" style={{ padding: 20 }}>
          <h3 style={{ marginBottom: 16, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
            📊 Chamadas URA por dia
          </h3>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={chartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
              <XAxis dataKey="date" tick={{ fill: '#94a3b8', fontSize: 12 }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} allowDecimals={false} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: '1px solid rgba(148,163,184,0.15)', borderRadius: 8 }}
                labelStyle={{ color: '#e2e8f0' }}
              />
              <Legend wrapperStyle={{ fontSize: 12, color: '#94a3b8' }} />
              <Bar dataKey="Chamadas" fill="#007aff" radius={[4,4,0,0]} cursor="pointer" onClick={drillDownToDay} />
              <Bar dataKey="Jira Abertas" fill="#3b82f6" radius={[4,4,0,0]} cursor="pointer" onClick={drillDownToDay} />
            </BarChart>
          </ResponsiveContainer>
          <div style={{ marginTop: 8, fontSize: '.75rem', color: 'var(--text-muted)' }}>
            Clique numa barra para ver as chamadas daquele dia
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Ranking de Atendimentos ─────────────────────────────────────────────────

interface RankingItem { label: string; total: number; }
interface AvgDurationItem { label: string; avgDurationSecs: number; }
interface RankingTrend {
  topClientsTotal: number; topClientsPrevTotal: number;
  byTypeTotal: number; byTypePrevTotal: number;
  topResolutionsTotal: number; topResolutionsPrevTotal: number;
  avgDurationSecs: number; avgDurationPrevSecs: number;
  subjectsTotalByType: Record<string, number>;
  subjectsPrevTotalByType: Record<string, number>;
}
interface RankingData {
  topClients: RankingItem[];
  byType: RankingItem[];
  topResolutions: RankingItem[];
  topSubjectsByType: Record<string, RankingItem[]>;
  avgDurationByType: AvgDurationItem[];
  trend: RankingTrend;
}

function formatDuration(secs: number): string {
  const m = Math.floor(secs / 60);
  const s = Math.round(secs % 60);
  return `${m}m ${s}s`;
}

/** "▲12% vs. período anterior" — omitido quando não há como comparar (período "Todo o período"). */
function TrendBadge({ current, previous }: { current: number; previous: number }) {
  if (previous === 0 && current === 0) return null;
  if (previous === 0) {
    return <span style={{ fontSize: '.72rem', color: '#34c759' }}>▲ novo neste período</span>;
  }
  const pct = Math.round(((current - previous) / previous) * 100);
  if (pct === 0) {
    return <span style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>— igual ao período anterior</span>;
  }
  const up = pct > 0;
  return (
    <span style={{ fontSize: '.72rem', color: up ? '#34c759' : '#ff3b30' }}>
      {up ? '▲' : '▼'} {Math.abs(pct)}% vs. período anterior
    </span>
  );
}

interface RankingDrillDownFilters {
  clientName?: string; callType?: string; subjectTag?: string; jiraResolution?: string;
  dateFrom?: string; dateTo?: string;
}

interface RankingQueryParams { period: string; uraId: string; dateFrom: string; dateTo: string; }

function buildRankingParams({ period, uraId, dateFrom, dateTo }: RankingQueryParams): Record<string, string> {
  const params: Record<string, string> = (dateFrom && dateTo) ? { dateFrom, dateTo } : { period };
  if (uraId) params.uraId = uraId;
  return params;
}

/** Baixa o CSV de um indicador da aba Ranking (GET /reports/ranking). */
async function exportRankingCsv(section: string, query: RankingQueryParams, callType?: string) {
  try {
    const params: Record<string, string> = { section, ...buildRankingParams(query) };
    if (callType) params.callType = callType;
    const response = await api.get('/reports/ranking', { params, responseType: 'blob' });
    const url = URL.createObjectURL(new Blob([response.data], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', `ranking_${section}.csv`);
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  } catch {
    alert('Erro ao exportar CSV. Tente novamente.');
  }
}

/** Placeholder de carregamento por card — evita substituir a tela inteira por um spinner único. */
function CardSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '4px 0' }}>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="skeleton-bar" style={{
          height: 16, borderRadius: 4, width: `${85 - i * 12}%`,
          background: 'linear-gradient(90deg, rgba(148,163,184,0.10), rgba(148,163,184,0.2), rgba(148,163,184,0.10))',
          backgroundSize: '200% 100%', animation: 'ranking-skeleton-pulse 1.4s ease-in-out infinite',
        }} />
      ))}
      <style>{`@keyframes ranking-skeleton-pulse { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }`}</style>
    </div>
  );
}

function RankingCard({ title, icon, items, emptyMessage, color, onExport, onBarClick, trend, loading, infoTooltip }: {
  title: string; icon: string; items: RankingItem[]; emptyMessage: string; color: string;
  onExport?: () => void; onBarClick?: (label: string) => void; trend?: { current: number; previous: number };
  loading?: boolean; infoTooltip?: string;
}) {
  return (
    <div className="card">
      <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h3 className="card-title">
            {icon} {title}
            {infoTooltip && <span title={infoTooltip} style={{ marginLeft: 6, cursor: 'help', color: 'var(--text-muted)' }}>ℹ️</span>}
          </h3>
          {trend && <TrendBadge current={trend.current} previous={trend.previous} />}
        </div>
        {onExport && (
          <button className="btn btn-ghost btn-sm" onClick={onExport} title="Exportar CSV">📤</button>
        )}
      </div>
      <div className="card-body">
        {loading ? (
          <CardSkeleton />
        ) : items.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '24px 0', color: 'var(--text-muted)', fontSize: '.85rem' }}>
            {emptyMessage}
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={Math.max(120, items.length * 42)}>
            <BarChart data={items} layout="vertical" margin={{ top: 4, right: 24, left: 8, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" horizontal={false} />
              <XAxis type="number" allowDecimals={false} tick={{ fill: '#94a3b8', fontSize: 12 }} />
              <YAxis type="category" dataKey="label" width={140} tick={{ fill: '#94a3b8', fontSize: 12 }} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: '1px solid rgba(148,163,184,0.15)', borderRadius: 8 }}
                labelStyle={{ color: '#e2e8f0' }}
              />
              <Bar
                dataKey="total" name="Chamadas" fill={color} radius={[0, 4, 4, 0]}
                cursor={onBarClick ? 'pointer' : undefined}
                onClick={onBarClick ? (entry: unknown) => {
                  const item = entry as { label?: string; payload?: { label?: string } };
                  const label = item.payload?.label ?? item.label;
                  if (label) onBarClick(label);
                } : undefined}
              />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

function AvgDurationCard({ items, onExport, onRowClick, trend, loading }: {
  items: AvgDurationItem[]; onExport: () => void; onRowClick: (label: string) => void;
  trend?: { current: number; previous: number }; loading?: boolean;
}) {
  return (
    <div className="card">
      <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h3 className="card-title">⏱️ Duração média por tipo</h3>
          {trend && <TrendBadge current={trend.current} previous={trend.previous} />}
        </div>
        <button className="btn btn-ghost btn-sm" onClick={onExport} title="Exportar CSV">📤</button>
      </div>
      <div className="card-body">
        {loading ? (
          <CardSkeleton rows={2} />
        ) : items.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '24px 0', color: 'var(--text-muted)', fontSize: '.85rem' }}>
            Nenhuma chamada classificada no período
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {items.map(item => (
              <div key={item.label}
                onClick={() => onRowClick(item.label)}
                style={{ display: 'flex', justifyContent: 'space-between', fontSize: '.9rem', cursor: 'pointer' }}
                title="Ver chamadas deste tipo"
              >
                <span style={{ color: 'var(--text-muted)' }}>{item.label}</span>
                <strong>{formatDuration(item.avgDurationSecs)}</strong>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// Ordem dos cards do Ranking é uma preferência pessoal — persistida no navegador
// do usuário, não no backend (não é um dado do negócio, é só layout).
const RANKING_CARD_ORDER_KEY = 'asteriskia.ranking.cardOrder';

function loadCardOrder(): string[] {
  try {
    const raw = localStorage.getItem(RANKING_CARD_ORDER_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function saveCardOrder(order: string[]) {
  try {
    localStorage.setItem(RANKING_CARD_ORDER_KEY, JSON.stringify(order));
  } catch {
    // localStorage indisponível (modo privado/quota) — ordem só não persiste entre sessões
  }
}

/** Wrapper arrastável — cada card do Ranking vira um item reordenável pelo usuário. */
function DraggableCard({ id, isDragging, onDragStart, onDragOver, onDrop, onDragEnd, children }: {
  id: string; isDragging: boolean;
  onDragStart: (id: string) => void;
  onDragOver: (e: React.DragEvent) => void;
  onDrop: (id: string) => void;
  onDragEnd: () => void;
  children: React.ReactNode;
}) {
  return (
    <div
      draggable
      onDragStart={() => onDragStart(id)}
      onDragOver={onDragOver}
      onDrop={() => onDrop(id)}
      onDragEnd={onDragEnd}
      style={{ opacity: isDragging ? 0.4 : 1, cursor: 'grab', position: 'relative' }}
      title="Arraste para reorganizar os cards"
    >
      <span style={{
        position: 'absolute', top: 10, right: 44, fontSize: '.9rem',
        color: 'var(--text-muted)', pointerEvents: 'none', zIndex: 1,
      }}>⠿</span>
      {children}
    </div>
  );
}

function RankingTab({ uras, onDrillDown }: { uras: Ura[]; onDrillDown: (filters: RankingDrillDownFilters) => void }) {
  const [data, setData] = useState<RankingData | null>(null);
  const [period, setPeriod] = useState<'today' | 'week' | 'month' | 'all'>('all');
  const [uraFilter, setUraFilter] = useState('');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [loading, setLoading] = useState(true);
  const [cardOrder, setCardOrder] = useState<string[]>(loadCardOrder);
  const [draggedId, setDraggedId] = useState<string | null>(null);

  const load = useCallback((query: RankingQueryParams) => {
    setLoading(true);
    const params = new URLSearchParams(buildRankingParams(query));
    api.get<RankingData>(`/stats/calls/ranking?${params}`)
      .then(r => setData(r.data))
      .finally(() => setLoading(false));
  }, []);

  const query: RankingQueryParams = { period, uraId: uraFilter, dateFrom: customFrom, dateTo: customTo };
  const hasCustomRange = !!(customFrom && customTo);
  // Sem "período anterior" bem definido para "Todo o período" — evita comparação sem sentido.
  const showTrend = hasCustomRange || period !== 'all';

  // Padrão "Todo o período" — garante que a aba já abra com o histórico existente,
  // em vez de "Esta semana" (que fica vazia sempre que não há chamada nos últimos dias).
  useEffect(() => { load({ period: 'all', uraId: '', dateFrom: '', dateTo: '' }); }, [load]);

  const selectPeriod = (p: typeof period) => {
    setPeriod(p); setCustomFrom(''); setCustomTo('');
    load({ period: p, uraId: uraFilter, dateFrom: '', dateTo: '' });
  };
  const applyCustomRange = () => {
    if (!customFrom || !customTo) return;
    load({ period, uraId: uraFilter, dateFrom: customFrom, dateTo: customTo });
  };
  const changeUra = (ura: string) => {
    setUraFilter(ura);
    load({ ...query, uraId: ura });
  };

  const trend = data?.trend;

  // Definições de card com id estável — a ordem aqui é só o fallback "padrão de
  // fábrica"; a ordem efetivamente exibida vem de cardOrder (arrastar-e-soltar,
  // persistido por navegador). Cards dinâmicos (um por call_type real observado)
  // usam id `subject:<callType>` para sobreviver entre reloads mesmo que os tipos
  // observados mudem de um período para outro.
  const cardDefs: { id: string; node: React.ReactNode }[] = [
    {
      id: 'topClients',
      node: (
        <RankingCard
          title="Clientes que mais ligam" icon="👤"
          items={data?.topClients ?? []} color="#007aff"
          emptyMessage="Nenhuma chamada com cliente identificado no período"
          onExport={() => exportRankingCsv('topClients', query)}
          onBarClick={label => onDrillDown({ clientName: label })}
          trend={showTrend && trend ? { current: trend.topClientsTotal, previous: trend.topClientsPrevTotal } : undefined}
          loading={loading}
        />
      ),
    },
    {
      id: 'byType',
      node: (
        <RankingCard
          title="Distribuição por tipo" icon="🏷️"
          items={data?.byType ?? []} color="#3b82f6"
          emptyMessage="Nenhuma chamada classificada no período"
          onExport={() => exportRankingCsv('byType', query)}
          onBarClick={label => onDrillDown({ callType: label })}
          trend={showTrend && trend ? { current: trend.byTypeTotal, previous: trend.byTypePrevTotal } : undefined}
          loading={loading}
        />
      ),
    },
    {
      id: 'topResolutions',
      node: (
        <RankingCard
          title="Soluções mais aplicadas (Jira)" icon="✅"
          items={data?.topResolutions ?? []} color="#34c759"
          emptyMessage="Nenhuma solução sincronizada ainda — o sync com o Jira roda periodicamente"
          onExport={() => exportRankingCsv('topResolutions', query)}
          onBarClick={label => onDrillDown({ jiraResolution: label })}
          trend={showTrend && trend ? { current: trend.topResolutionsTotal, previous: trend.topResolutionsPrevTotal } : undefined}
          loading={loading}
          infoTooltip="Só aparece aqui a chamada que abriu um chamado no Jira — o status/resolução é sincronizado a cada 15 minutos pelo JiraSyncScheduler, olhando só os últimos 90 dias. Chamadas sem chamado Jira aberto (integração desativada na URA, ou falha ao criar o issue) nunca vão aparecer neste card."
        />
      ),
    },
    {
      id: 'avgDuration',
      node: (
        <AvgDurationCard
          items={data?.avgDurationByType ?? []}
          onExport={() => exportRankingCsv('avgDurationByType', query)}
          onRowClick={label => onDrillDown({ callType: label })}
          trend={showTrend && trend ? { current: trend.avgDurationSecs, previous: trend.avgDurationPrevSecs } : undefined}
          loading={loading}
        />
      ),
    },
    ...Object.entries(data?.topSubjectsByType ?? {}).map(([callType, items]) => ({
      id: `subject:${callType}`,
      node: (
        <RankingCard
          title={`Mais pedido em "${callType}"`} icon="🎯"
          items={items} color="#ff9f0a"
          emptyMessage="Nenhum assunto classificado ainda no período — a classificação por IA roda ao fim de cada chamada"
          onExport={() => exportRankingCsv('topSubjectsByType', query, callType)}
          onBarClick={label => onDrillDown({ callType, subjectTag: label })}
          trend={showTrend && trend ? {
            current: trend.subjectsTotalByType[callType] ?? 0,
            previous: trend.subjectsPrevTotalByType[callType] ?? 0,
          } : undefined}
        />
      ),
    })),
  ];

  // Aplica a ordem salva (cards conhecidos primeiro, na ordem arrastada) e anexa
  // no fim qualquer card novo que o usuário ainda não viu/reordenou.
  const knownIds = new Set(cardDefs.map(c => c.id));
  const orderedIds = [
    ...cardOrder.filter(id => knownIds.has(id)),
    ...cardDefs.map(c => c.id).filter(id => !cardOrder.includes(id)),
  ];
  const cardById = new Map(cardDefs.map(c => [c.id, c]));

  const handleDragStart = (id: string) => setDraggedId(id);
  const handleDragOver = (e: React.DragEvent) => e.preventDefault();
  const handleDragEnd = () => setDraggedId(null);
  const handleDrop = (targetId: string) => {
    if (!draggedId || draggedId === targetId) return;
    const next = [...orderedIds];
    const from = next.indexOf(draggedId);
    const to = next.indexOf(targetId);
    if (from === -1 || to === -1) return;
    next.splice(from, 1);
    next.splice(to, 0, draggedId);
    setCardOrder(next);
    saveCardOrder(next);
    setDraggedId(null);
  };
  const resetCardOrder = () => { setCardOrder([]); saveCardOrder([]); };

  return (
    <div>
      <div className="flex gap-1" style={{ marginBottom: 16, flexWrap: 'wrap', alignItems: 'center' }}>
        {(['today', 'week', 'month', 'all'] as const).map(p => (
          <button key={p}
            className={`btn btn-sm ${period === p && !hasCustomRange ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => selectPeriod(p)}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : p === 'month' ? 'Este mês' : 'Todo o período'}
          </button>
        ))}
        <span style={{ color: 'var(--text-muted)', fontSize: '.8rem', margin: '0 4px' }}>ou período customizado:</span>
        <input type="date" className="form-input" style={{ maxWidth: 150 }} value={customFrom}
          onChange={e => setCustomFrom(e.target.value)} />
        <input type="date" className="form-input" style={{ maxWidth: 150 }} value={customTo}
          onChange={e => setCustomTo(e.target.value)} />
        <button className={`btn btn-sm ${hasCustomRange ? 'btn-primary' : 'btn-ghost'}`}
          onClick={applyCustomRange} disabled={!customFrom || !customTo}>
          Aplicar
        </button>
        <select
          className="form-select" style={{ maxWidth: 220, marginLeft: 8 }}
          value={uraFilter}
          onChange={e => changeUra(e.target.value)}
        >
          <option value="">Todas as URAs</option>
          {uras.map(u => (
            <option key={u.id} value={u.id}>{u.name} (ramal {u.extension})</option>
          ))}
        </select>
        {cardOrder.length > 0 && (
          <button className="btn btn-ghost btn-sm" onClick={resetCardOrder} title="Restaurar a ordem padrão dos cards">
            ↺ Restaurar ordem
          </button>
        )}
      </div>
      {/* Grade sempre renderizada — cada card mostra seu próprio skeleton enquanto
          carrega, em vez de trocar a tela inteira por um spinner único. Cada card é
          arrastável — a ordem escolhida pelo usuário é salva no navegador. */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 16 }}>
        {orderedIds.map(id => {
          const def = cardById.get(id);
          if (!def) return null;
          return (
            <DraggableCard
              key={id} id={id} isDragging={draggedId === id}
              onDragStart={handleDragStart} onDragOver={handleDragOver}
              onDrop={handleDrop} onDragEnd={handleDragEnd}
            >
              {def.node}
            </DraggableCard>
          );
        })}
      </div>
    </div>
  );
}

// ─── KPI cards ───────────────────────────────────────────────────────────────

interface CallStats {
  totalCalls: number;
  callsWithJira: number;
  jiraSuccessRatePct: number;
  avgDurationSecs: number;
}

function KpiBar() {
  const [stats, setStats] = useState<CallStats | null>(null);
  const [period, setPeriod] = useState<'today' | 'week' | 'month'>('today');

  const load = useCallback((p: typeof period) => {
    api.get<CallStats>(`/stats/calls?period=${p}`).then(r => setStats(r.data));
  }, []);

  // Ref sempre atualizado com o período atual — evita stale closure no callback do WebSocket
  const periodRef = useRef(period);
  useEffect(() => { periodRef.current = period; }, [period]);

  useEffect(() => {
    load('today');
    const unsub = subscribe('/topic/calls', () => load(periodRef.current));
    return () => unsub?.();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (!stats) return null;

  const avgMin = stats.avgDurationSecs > 0
    ? `${Math.floor(stats.avgDurationSecs / 60)}m ${stats.avgDurationSecs % 60}s`
    : '—';

  return (
    <div style={{ marginBottom: 16 }}>
      <div className="flex gap-1" style={{ marginBottom: 10 }}>
        {(['today', 'week', 'month'] as const).map(p => (
          <button key={p} className={`btn btn-sm ${period === p ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => { setPeriod(p); load(p); }}>
            {p === 'today' ? 'Hoje' : p === 'week' ? 'Esta semana' : 'Este mês'}
          </button>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 10 }}>
        {[
          { label: 'Chamadas URA',  value: stats.totalCalls,               color: '#007aff' },
          { label: 'Chamados Jira', value: stats.callsWithJira,            color: '#3b82f6' },
          { label: 'Taxa Jira',     value: `${stats.jiraSuccessRatePct}%`, color: '#34c759' },
          { label: 'Duração Média', value: avgMin,                         color: '#ff9f0a' },
        ].map(kpi => (
          <div key={kpi.label} className="stat-card" style={{ padding: '12px 16px' }}>
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>{kpi.label}</div>
            <div style={{ fontSize: '1.4rem', fontWeight: 700, color: kpi.color }}>{kpi.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Player de áudio inline ───────────────────────────────────────────────────

function AudioPlayer({ callId }: { callId: number }) {
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
      path={`/calls/${callId}/audio`}
      autoPlay
      style={{ height: 28, minWidth: 180, maxWidth: 240 }}
      onError={() => setShow(false)}
    />
  );
}

// ─── Módulo URA principal ────────────────────────────────────────────────────

export default function ModuloURA() {
  const [tab, setTab] = useState<'calls' | 'dashboard' | 'uras' | 'ranking'>('calls');
  const [uras, setUras] = useState<Ura[]>([]);
  const [calls, setCalls] = useState<CallRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [search, setSearch] = useState('');
  const [exporting, setExporting] = useState(false);
  const [detailCall, setDetailCall] = useState<CallRecord | null>(null);

  // Filtros avançados (colapsáveis)
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [clientNameFilter, setClientNameFilter] = useState('');
  const [ramalFilter, setRamalFilter] = useState('');
  const [callTypeFilter, setCallTypeFilter] = useState('');
  const [jiraKeyFilter, setJiraKeyFilter] = useState('');
  const [transcriptionFilter, setTranscriptionFilter] = useState('');
  const [priorityFilter, setPriorityFilter] = useState('');
  const [uraFilter, setUraFilter] = useState('');
  const [subjectTagFilter, setSubjectTagFilter] = useState('');
  const [jiraResolutionFilter, setJiraResolutionFilter] = useState('');

  const hasActiveFilters = !!(dateFrom || dateTo || clientNameFilter || ramalFilter
    || callTypeFilter || jiraKeyFilter || transcriptionFilter || priorityFilter || uraFilter
    || subjectTagFilter || jiraResolutionFilter);

  const loadCalls = (p = 0) => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(p), size: '20' });
    if (search) params.set('callerNumber', search);
    if (dateFrom) params.set('dateFrom', dateFrom);
    if (dateTo) params.set('dateTo', dateTo);
    if (clientNameFilter) params.set('clientName', clientNameFilter);
    if (ramalFilter) params.set('ramal', ramalFilter);
    if (callTypeFilter) params.set('callType', callTypeFilter);
    if (jiraKeyFilter) params.set('jiraIssueKey', jiraKeyFilter);
    if (transcriptionFilter) params.set('transcriptionText', transcriptionFilter);
    if (priorityFilter) params.set('priority', priorityFilter);
    if (uraFilter) params.set('uraId', uraFilter);
    if (subjectTagFilter) params.set('subjectTag', subjectTagFilter);
    if (jiraResolutionFilter) params.set('jiraResolution', jiraResolutionFilter);
    api.get<PageResponse<CallRecord>>(`/calls?${params}`)
      .then(r => {
        setCalls(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    api.get<Ura[]>('/uras').then(r => setUras(r.data));
  }, []);

  useEffect(() => {
    if (tab === 'calls') loadCalls(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const handleSearchSubmit = (e: React.FormEvent) => { e.preventDefault(); loadCalls(0); };

  const clearFilters = () => {
    setDateFrom(''); setDateTo(''); setClientNameFilter(''); setRamalFilter('');
    setCallTypeFilter(''); setJiraKeyFilter(''); setTranscriptionFilter(''); setPriorityFilter(''); setUraFilter('');
    setSubjectTagFilter(''); setJiraResolutionFilter('');
    // Recarrega já sem os filtros — precisa esperar o próximo tick para os estados aplicarem
    setTimeout(() => loadCalls(0), 0);
  };

  /**
   * Drill-down vindo do Ranking de Atendimentos ou do Dashboard: troca para a aba
   * Chamadas já com o filtro correspondente ao ponto clicado (barra/linha do
   * Ranking, ou dia do gráfico do Dashboard) — os demais filtros avançados são
   * limpos para não combinar com um recorte anterior sem o usuário perceber.
   */
  const handleDrillDown = (filters: RankingDrillDownFilters) => {
    setRamalFilter(''); setJiraKeyFilter('');
    setTranscriptionFilter(''); setPriorityFilter(''); setUraFilter('');
    setClientNameFilter(filters.clientName ?? '');
    setCallTypeFilter(filters.callType ?? '');
    setSubjectTagFilter(filters.subjectTag ?? '');
    setJiraResolutionFilter(filters.jiraResolution ?? '');
    setDateFrom(filters.dateFrom ?? '');
    setDateTo(filters.dateTo ?? '');
    setFiltersOpen(true);
    setTab('calls');
  };

  const priorityBadge = (value?: string) => {
    if (!value) return <span className="text-muted">—</span>;
    const v = value.toLowerCase();
    const cls = v.includes('alta') ? 'badge-danger' : v.includes('méd') || v.includes('med') ? 'badge-warning'
      : v.includes('baix') ? 'badge-success' : 'badge-gray';
    return <span className={`badge ${cls}`}>{value}</span>;
  };

  const exportUra = async () => {
    setExporting(true);
    try {
      const response = await api.get(`/calls/export`, { responseType: 'blob' });
      const url  = URL.createObjectURL(new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `chamadas_ura.xlsx`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch {
      alert('Erro ao exportar chamadas. Tente novamente.');
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <div className="page-header">
        <h1>🎫 URA</h1>
        <p>Histórico de chamadas e configuração do fluxo de atendimento</p>
      </div>
      <div className="page-body">

        <KpiBar />

        {/* Tabs */}
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20, display: 'flex', gap: 6 }}>
          <button className={`btn ${tab === 'uras'      ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('uras')}>
            🎛️ URAs
          </button>
          <button className={`btn ${tab === 'dashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('dashboard')}>
            📊 Dashboard
          </button>
          <button className={`btn ${tab === 'calls'     ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('calls')}>
            📋 Chamadas
          </button>
          <button className={`btn ${tab === 'ranking'   ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('ranking')}>
            🏆 Ranking de Atendimentos
          </button>
        </div>

        {/* ---- CALLS TAB ---- */}
      {/* Modal detalhe da chamada */}
      {detailCall && (
        <div className="modal-overlay" onClick={() => setDetailCall(null)}>
          <div className="modal" style={{ maxWidth: 640, width: '96vw' }} onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h3 style={{ fontSize: '1rem', fontWeight: 600 }}>
                Chamada #{detailCall.id} — {formatDate(detailCall.callDate)}
              </h3>
              <button className="btn-close" onClick={() => setDetailCall(null)}>×</button>
            </div>
            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

              {/* Info básica */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Número</div>
                  <div className="mono" style={{ fontSize: '.9rem' }}>{detailCall.callerNumber}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Status</div>
                  <span className="badge badge-info">{detailCall.jiraIssueStatus || 'Aberto'}</span>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Cliente</div>
                  <div style={{ fontSize: '.9rem' }}>{detailCall.clientName || detailCall.callerNumber || '—'}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Tipo</div>
                  <div style={{ fontSize: '.9rem' }}>{detailCall.callType || '—'}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Ramal informado</div>
                  <div className="mono" style={{ fontSize: '.9rem' }}>{detailCall.reportedRamal || '—'}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Impacto</div>
                  <div style={{ fontSize: '.9rem' }}>{priorityBadge(detailCall.priority)}</div>
                </div>
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Chamado Jira</div>
                  <div style={{ fontSize: '.9rem' }}>{detailCall.jiraIssueKey
                    ? <span className="chip">{detailCall.jiraIssueKey}</span>
                    : '—'}
                  </div>
                </div>
              </div>

              {/* Player de áudio */}
              <div>
                <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Gravação da chamada</div>
                {detailCall.audioFilePath ? (
                  <AuthedAudio path={`/calls/${detailCall.id}/audio`} style={{ width: '100%', height: 36 }} />
                ) : (
                  <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>Gravação não disponível</span>
                )}
              </div>

              {/* Respostas por pergunta */}
              {detailCall.answers && detailCall.answers.length > 0 && (
                <div>
                  <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Respostas por pergunta</div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                    {detailCall.answers.map(a => (
                      <div key={a.questionId} style={{
                        background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                        borderRadius: 8, padding: '8px 12px',
                      }}>
                        <div style={{ fontSize: '.7rem', color: 'var(--text-muted)', marginBottom: 3 }}>{a.questionText}</div>
                        <div style={{ fontSize: '.85rem', fontWeight: 500 }}>{a.value || '—'}</div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Transcrição completa */}
              <div>
                <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Transcrição completa</div>
                {detailCall.transcription ? (
                  <div style={{
                    background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                    borderRadius: 8, padding: '12px 14px',
                    maxHeight: 280, overflowY: 'auto',
                    display: 'flex', flexDirection: 'column', gap: 12,
                  }}>
                    {detailCall.transcription.split('\n').filter(l => l.trim()).map((line, i) => {
                      // Formato salvo pelo ai-agent: [pergunta da URA]: resposta do cliente
                      const match = line.match(/^\[(.+?)\]:\s*(.*)$/);
                      if (!match) {
                        return (
                          <div key={i} style={{ fontSize: '.82rem', color: 'var(--text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                            {line}
                          </div>
                        );
                      }
                      const [, pergunta, resposta] = match;
                      return (
                        <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                            <span className="badge badge-info" style={{ fontSize: '.62rem', flexShrink: 0, marginTop: 1 }}>URA</span>
                            <span style={{ fontSize: '.8rem', color: 'var(--text-muted)', fontStyle: 'italic', wordBreak: 'break-word' }}>{pergunta}</span>
                          </div>
                          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, paddingLeft: 6 }}>
                            <span className="badge badge-success" style={{ fontSize: '.62rem', flexShrink: 0, marginTop: 1 }}>Cliente</span>
                            <span style={{ fontSize: '.85rem', color: 'var(--text-primary)', fontWeight: 500, wordBreak: 'break-word' }}>{resposta || '—'}</span>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>Transcrição não disponível</span>
                )}
              </div>

            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setDetailCall(null)}>Fechar</button>
            </div>
          </div>
        </div>
      )}


        {tab === 'calls' && (
          <>
            <div className="toolbar">
              <form className="toolbar-left" onSubmit={handleSearchSubmit}>
                <div className="search-wrapper">
                  <span className="search-icon">🔍</span>
                  <input className="search-input" aria-label="Filtrar por número" placeholder="Filtrar por número..."
                    value={search} onChange={e => setSearch(e.target.value)} />
                </div>
                <button type="submit" className="btn btn-ghost btn-sm">Buscar</button>
                <button
                  type="button"
                  className={`btn btn-sm ${hasActiveFilters ? 'btn-primary' : 'btn-ghost'}`}
                  onClick={() => setFiltersOpen(o => !o)}
                >
                  🔧 Filtros{hasActiveFilters ? ' •' : ''}
                </button>
              </form>
              <div className="toolbar-right">
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={exportUra}
                  disabled={exporting}
                  style={{ borderColor: 'rgba(0,122,255,0.4)', color: '#4da8ff', minWidth: 140 }}
                >
                  {exporting
                    ? <><span className="spinner" style={{ width: 12, height: 12, margin: '0 6px 0 0' }} />Exportando…</>
                    : '⬇ Exportar CSV'}
                </button>
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
                  <label className="form-label">Login / Cliente</label>
                  <input className="form-input" placeholder="ex: kaio.correa" value={clientNameFilter} onChange={e => setClientNameFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Ramal informado</label>
                  <input className="form-input" placeholder="ex: 5004" value={ramalFilter} onChange={e => setRamalFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Tipo</label>
                  <select className="form-select" value={callTypeFilter} onChange={e => setCallTypeFilter(e.target.value)}>
                    <option value="">Todos</option>
                    <option value="Incidente">Incidente</option>
                    <option value="Requisição">Requisição</option>
                  </select>
                </div>
                <div>
                  <label className="form-label">Chamado Jira</label>
                  <input className="form-input" placeholder="ex: SUPP-123" value={jiraKeyFilter} onChange={e => setJiraKeyFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Impacto</label>
                  <select className="form-select" value={priorityFilter} onChange={e => setPriorityFilter(e.target.value)}>
                    <option value="">Todos</option>
                    <option value="Baixa">Baixa</option>
                    <option value="Média">Média</option>
                    <option value="Alta">Alta</option>
                  </select>
                </div>
                <div>
                  <label className="form-label">Texto na transcrição</label>
                  <input className="form-input" placeholder="ex: computador reiniciando" value={transcriptionFilter} onChange={e => setTranscriptionFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Assunto (IA)</label>
                  <input className="form-input" placeholder="ex: Reset de senha" value={subjectTagFilter} onChange={e => setSubjectTagFilter(e.target.value)} />
                </div>
                <div>
                  <label className="form-label">Solução (Jira)</label>
                  <input className="form-input" placeholder="ex: Resolvido" value={jiraResolutionFilter} onChange={e => setJiraResolutionFilter(e.target.value)} />
                </div>
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8 }}>
                  <button className="btn btn-primary btn-sm" onClick={() => loadCalls(0)}>Aplicar filtros</button>
                  <button className="btn btn-ghost btn-sm" onClick={clearFilters} disabled={!hasActiveFilters}>Limpar</button>
                </div>
              </div>
            )}

            {loading ? (
              <div className="loading-state"><div className="spinner" />Carregando chamadas…</div>
            ) : (
              <div className="table-wrapper">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Data / Hora</th>
                      <th>Número</th>
                      <th>Cliente</th>
                      <th>Tipo</th>
                      <th>Impacto</th>
                      <th>Chamado Jira</th>
                      <th>Status</th>
                      <th>Duração</th>
                      <th>Áudio</th>
                      <th>Transcrição</th>
                    </tr>
                  </thead>
                  <tbody>
                    {calls.length === 0 ? (
                      <tr><td colSpan={11} className="table-empty">Nenhuma chamada registrada</td></tr>
                    ) : calls.map(c => (
                      <tr key={c.id}
                        onClick={() => setDetailCall(c)}
                        style={{ cursor: 'pointer' }}
                        title="Clique para ver detalhes"
                      >
                        <td className="td-muted">{c.id}</td>
                        <td className="td-muted">{formatDate(c.callDate)}</td>
                        <td className="mono">{c.callerNumber}</td>
                        <td>{c.clientName || c.callerNumber || <span className="text-muted">—</span>}</td>
                        <td>
                          {c.callType
                            ? <span className="badge" style={{ background: c.callType.toLowerCase().includes('incidente') ? 'rgba(255,107,107,0.1)' : 'rgba(0,122,255,0.1)', color: c.callType.toLowerCase().includes('incidente') ? '#b3342f' : '#4da8ff' }}>{c.callType}</span>
                            : <span className="text-muted">—</span>}
                        </td>
                        <td>{priorityBadge(c.priority)}</td>
                        <td>
                          {c.jiraIssueKey
                            ? <span className="chip">{c.jiraIssueKey}</span>
                            : <span className="text-muted">—</span>}
                        </td>
                        <td><span className="badge badge-info">{c.jiraIssueStatus || 'Aberto'}</span></td>
                        <td className="td-muted">{c.callDurationSecs}s</td>
                        <td onClick={e => e.stopPropagation()}>
                          {c.audioFilePath
                            ? <AudioPlayer callId={c.id} />
                            : <span className="text-muted">—</span>}
                        </td>
                        <td className="td-muted" style={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {c.transcription
                            ? <span style={{ opacity: .7 }}>{c.transcription.slice(0, 60)}{c.transcription.length > 60 ? '…' : ''}</span>
                            : <span className="text-muted">—</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="pagination">
                  <span className="pagination-info">{calls.length} registros nesta página</span>
                  <div className="pagination-btns">
                    <button className="page-btn" disabled={page === 0} onClick={() => loadCalls(page - 1)}>‹</button>
                    {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => (
                      <button key={i} className={`page-btn ${i === page ? 'active' : ''}`} onClick={() => loadCalls(i)}>
                        {i + 1}
                      </button>
                    ))}
                    <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => loadCalls(page + 1)}>›</button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {/* ---- DASHBOARD TAB ---- */}
        {tab === 'dashboard' && <DashboardTab onDrillDown={handleDrillDown} />}

        {/* ---- RANKING TAB ---- */}
        {tab === 'ranking' && <RankingTab uras={uras} onDrillDown={handleDrillDown} />}

        {/* ---- URAs TAB ---- */}
        {tab === 'uras' && <UraManagementTab />}

      </div>
    </>
  );
}
