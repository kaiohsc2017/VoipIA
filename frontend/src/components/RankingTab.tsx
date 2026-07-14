import { useEffect, useState, useCallback } from 'react';
import api from '../api/client';
import type { Ura } from '../api/types';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';

interface RankingItem { label: string; total: number; }
interface AvgDurationItem { label: string; avgDurationSecs: number; }

// Teto de itens exibidos por indicador (Top 5) — garante que todo card do Ranking
// tenha a mesma altura, independente de quantos itens o backend retorne.
const TOP_N = 5;
const ROW_HEIGHT = 42;
const LIST_HEIGHT = TOP_N * ROW_HEIGHT;
function topN<T>(items: T[]): T[] {
  return items.slice(0, TOP_N);
}
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

export interface RankingDrillDownFilters {
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
function CardSkeleton({ rows = TOP_N }: { rows?: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '4px 0', minHeight: LIST_HEIGHT }}>
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
      <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', minHeight: 64 }}>
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
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: LIST_HEIGHT, textAlign: 'center', color: 'var(--text-muted)', fontSize: '.85rem' }}>
            {emptyMessage}
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={LIST_HEIGHT}>
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
      <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', minHeight: 64 }}>
        <div>
          <h3 className="card-title">⏱️ Duração média por tipo</h3>
          {trend && <TrendBadge current={trend.current} previous={trend.previous} />}
        </div>
        <button className="btn btn-ghost btn-sm" onClick={onExport} title="Exportar CSV">📤</button>
      </div>
      <div className="card-body">
        {loading ? (
          <CardSkeleton />
        ) : items.length === 0 ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: LIST_HEIGHT, textAlign: 'center', color: 'var(--text-muted)', fontSize: '.85rem' }}>
            Nenhuma chamada classificada no período
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', minHeight: LIST_HEIGHT }}>
            {items.map(item => (
              <div key={item.label}
                onClick={() => onRowClick(item.label)}
                style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', height: ROW_HEIGHT, fontSize: '.9rem', cursor: 'pointer' }}
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

export function RankingTab({ uras, onDrillDown }: { uras: Ura[]; onDrillDown: (filters: RankingDrillDownFilters) => void }) {
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
          items={topN(data?.topClients ?? [])} color="#007aff"
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
          items={topN(data?.byType ?? [])} color="#3b82f6"
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
          items={topN(data?.topResolutions ?? [])} color="#34c759"
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
          items={topN(data?.avgDurationByType ?? [])}
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
          items={topN(items)} color="#ff9f0a"
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
