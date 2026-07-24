import { useEffect, useRef, useState } from 'react';
import api, { getRoleFromToken } from '../api/client';
import type { InsightsListItem, InsightsDetailResponse, PageResponse } from '../api/types';
import type { InsightsDrillDownFilters } from '../api/types';
import { AuthedAudio } from './AuthedAudio';

const isAdmin = getRoleFromToken(localStorage.getItem('asteriskia_token')) === 'ADMIN';

const TONE_OPTIONS = ['calmo', 'neutro', 'tenso', 'irritado', 'empolgado'];
const CRITICIDADE_OPTIONS = ['baixa', 'media', 'alta', 'urgente'];
const FINDING_TYPE_OPTIONS = ['melhoria', 'falha', 'treinamento', 'tendencia'];

const FINDING_LABELS: Record<string, string> = {
  melhoria: '💡 Melhoria',
  falha: '⚠️ Falha de processo',
  treinamento: '🎓 Treinamento',
  tendencia: '📈 Tendência',
};

function formatDate(iso?: string) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

function formatDuration(seconds?: number) {
  if (!seconds) return '—';
  const min = Math.floor(seconds / 60);
  const sec = seconds % 60;
  return `${min}:${String(sec).padStart(2, '0')}`;
}

function toTitleCase(name: string): string {
  return name
    .toLowerCase()
    .split(' ')
    .map(word => (word ? word[0].toUpperCase() + word.slice(1) : word))
    .join(' ');
}

function criticidadeBadge(value?: string) {
  if (!value) return <span className="text-muted">—</span>;
  const cls = value === 'urgente' ? 'badge-danger' : value === 'alta' ? 'badge-warning'
    : value === 'media' ? 'badge-info' : 'badge-success';
  return <span className={`badge ${cls}`}>{value}</span>;
}

function directionBadge(value?: string) {
  if (!value) return <span className="text-muted">—</span>;
  return <span className="badge badge-info">{value === 'inbound' ? 'Recebida' : 'Efetuada'}</span>;
}

function disconnectedByBadge(value?: string) {
  if (!value) return <span className="text-muted">—</span>;
  return <span className="badge badge-gray">{value === 'atendente' ? 'Atendente' : 'Cliente'}</span>;
}

/** Coluna "Ramal destino"/"Atendente destino": traço quando a chamada não teve
 * transferência; badge neutro "Não identificado" quando transferiu mas a
 * correlação (ver TransferResolutionService) ainda não encontrou a perna de
 * destino — estado normal e esperado, não um erro (ver decisão 5/6 do plano). */
function transferTargetCell(numberOfTransfers: number | undefined, value: string | undefined) {
  if (!numberOfTransfers || numberOfTransfers <= 0) return <span className="text-muted">—</span>;
  if (value) return <span>{value}</span>;
  return <span className="badge badge-gray" title="Transferiu, mas a gravação de destino ainda não foi correlacionada">Não identificado</span>;
}

function speakerBadge(speaker: string) {
  const cls = speaker === 'agente' ? 'badge-info' : speaker === 'cliente' ? 'badge-success' : 'badge-gray';
  const label = speaker === 'agente' ? 'Atendente' : speaker === 'cliente' ? 'Cliente' : 'Indefinido';
  return <span className={`badge ${cls}`} style={{ fontSize: '.62rem', flexShrink: 0 }}>{label}</span>;
}

function toneBadge(label: string | undefined, tone?: string) {
  if (!tone) return null;
  return <span className="chip" style={{ fontSize: '.68rem' }} title={label}>{tone}</span>;
}

interface InsightsTabProps {
  pendingDrillDown?: { filters: InsightsDrillDownFilters; nonce: number } | null;
  onDrillDownConsumed?: () => void;
}

export function InsightsTab({ pendingDrillDown, onDrillDownConsumed }: InsightsTabProps) {
  const [items, setItems] = useState<InsightsListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);

  const [text, setText] = useState('');
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [phrase, setPhrase] = useState('');
  const [toneCliente, setToneCliente] = useState('');
  const [toneAtendente, setToneAtendente] = useState('');
  const [categoria, setCategoria] = useState('');
  const [criticidade, setCriticidade] = useState('');
  const [findingType, setFindingType] = useState('');
  const [agentName, setAgentName] = useState('');
  const [direction, setDirection] = useState('');
  const [skill, setSkill] = useState('');
  const [durationMin, setDurationMin] = useState('');
  const [durationMax, setDurationMax] = useState('');
  const [isFailedFilter, setIsFailedFilter] = useState('');
  // ─── V43 — filtros novos (decisão 8 do plano insights-chamadas-campos-xml) ───
  const [customerNumberFilter, setCustomerNumberFilter] = useState('');
  const [extensionFilter, setExtensionFilter] = useState('');
  const [disconnectedByFilter, setDisconnectedByFilter] = useState('');
  const [hasHoldFilter, setHasHoldFilter] = useState(false);
  const [wrapupTimeMin, setWrapupTimeMin] = useState('');
  const [wrapupTimeMax, setWrapupTimeMax] = useState('');
  const [transferTargetExtension, setTransferTargetExtension] = useState('');
  const [transferTargetAgentName, setTransferTargetAgentName] = useState('');
  const [targetSwitchCallId, setTargetSwitchCallId] = useState('');

  const hasActiveFilters = !!(dateFrom || dateTo || phrase || toneCliente || toneAtendente || categoria
    || criticidade || findingType || agentName || direction || skill || durationMin || durationMax || isFailedFilter
    || customerNumberFilter || extensionFilter || disconnectedByFilter || hasHoldFilter || wrapupTimeMin
    || wrapupTimeMax || transferTargetExtension || transferTargetAgentName || targetSwitchCallId);

  const [detailId, setDetailId] = useState<number | null>(null);
  const [detail, setDetail] = useState<InsightsDetailResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  /** overrides permite disparar a busca com valores que ainda não foram aplicados
   * ao estado local (drill-down do Dashboard de Tendências) — setState é
   * assíncrono, então ler o estado logo após chamá-lo pegaria o valor antigo. */
  const loadCalls = (p = 0, overrides: Partial<InsightsDrillDownFilters> = {}) => {
    setLoading(true);
    const effectiveCategoria = overrides.categoria ?? categoria;
    const effectiveCriticidade = overrides.criticidade ?? criticidade;
    const effectiveFindingType = overrides.findingType ?? findingType;
    const effectiveIsFailed = overrides.isFailed ?? (isFailedFilter ? isFailedFilter === 'true' : undefined);
    const params = new URLSearchParams({ page: String(p), size: '20' });
    if (overrides.id != null) params.set('id', String(overrides.id));
    if (text) params.set('text', text);
    if (dateFrom) params.set('dateFrom', dateFrom);
    if (dateTo) params.set('dateTo', dateTo);
    if (phrase) params.set('phrase', phrase);
    if (toneCliente) params.set('toneCliente', toneCliente);
    if (toneAtendente) params.set('toneAtendente', toneAtendente);
    if (effectiveCategoria) params.set('categoria', effectiveCategoria);
    if (effectiveCriticidade) params.set('criticidade', effectiveCriticidade);
    if (effectiveFindingType) params.set('findingType', effectiveFindingType);
    if (agentName) params.set('agentName', agentName);
    if (direction) params.set('direction', direction);
    if (skill) params.set('skill', skill);
    if (durationMin) params.set('durationMin', durationMin);
    if (durationMax) params.set('durationMax', durationMax);
    if (effectiveIsFailed != null) params.set('isFailed', String(effectiveIsFailed));
    if (customerNumberFilter) params.set('customerNumber', customerNumberFilter);
    if (extensionFilter) params.set('extension', extensionFilter);
    if (disconnectedByFilter) params.set('disconnectedBy', disconnectedByFilter);
    if (hasHoldFilter) params.set('hasHold', 'true');
    if (wrapupTimeMin) params.set('wrapupTimeMin', wrapupTimeMin);
    if (wrapupTimeMax) params.set('wrapupTimeMax', wrapupTimeMax);
    if (transferTargetExtension) params.set('transferTargetExtension', transferTargetExtension);
    if (transferTargetAgentName) params.set('transferTargetAgentName', transferTargetAgentName);
    if (isAdmin && targetSwitchCallId) params.set('targetSwitchCallId', targetSwitchCallId);
    api.get<PageResponse<InsightsListItem>>(`/insights/calls?${params}`)
      .then(r => {
        setItems(r.data.content ?? []);
        setTotalPages(r.data.totalPages);
        setPage(r.data.number);
      })
      .catch(err => {
        console.error('Erro ao carregar chamadas de Insights:', err);
        setItems([]);
      })
      .finally(() => setLoading(false));
  };

  // Captura o valor de montagem — se já chegou com um drill-down pendente, o
  // efeito abaixo cuida da busca; sem essa checagem, os dois efeitos disparam
  // no mesmo mount e criam duas requisições concorrentes (a sem filtro pode
  // "vencer" a filtrada e sobrescrever a lista com o resultado errado).
  const mountedWithDrillDown = useRef(pendingDrillDown != null);
  useEffect(() => {
    if (!mountedWithDrillDown.current) loadCalls(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Drill-down vindo do Dashboard de Tendências, Custos IA ou Processamento: limpa
   * os demais filtros (para não combinar com um recorte anterior sem o usuário
   * perceber, mesmo critério do ModuloURA.handleDrillDown), aplica o filtro do
   * indicador/linha clicada, já dispara a busca e avisa o pai que o drill-down foi
   * consumido — InsightsTab desmonta/remonta a cada troca de aba, então sem isso o
   * mesmo drill-down seria reaplicado numa volta manual pra aba Chamadas.
   * `id` (Custos IA/Processamento) filtra uma chamada exata — não tem campo próprio
   * no formulário de filtros, é só um override pontual desta busca. */
  useEffect(() => {
    if (!pendingDrillDown) return;
    const { id: newId, categoria: newCategoria, criticidade: newCriticidade, findingType: newFindingType, isFailed: newIsFailed } = pendingDrillDown.filters;
    setText(''); setDateFrom(''); setDateTo(''); setPhrase(''); setToneCliente(''); setToneAtendente('');
    setAgentName(''); setDirection(''); setSkill(''); setDurationMin(''); setDurationMax('');
    setCustomerNumberFilter(''); setExtensionFilter(''); setDisconnectedByFilter(''); setHasHoldFilter(false);
    setWrapupTimeMin(''); setWrapupTimeMax(''); setTransferTargetExtension(''); setTransferTargetAgentName('');
    setTargetSwitchCallId('');
    setCategoria(newCategoria ?? '');
    setCriticidade(newCriticidade ?? '');
    setFindingType(newFindingType ?? '');
    setIsFailedFilter(newIsFailed != null ? String(newIsFailed) : '');
    setFiltersOpen(true);
    loadCalls(0, { id: newId, categoria: newCategoria, criticidade: newCriticidade, findingType: newFindingType, isFailed: newIsFailed });
    onDrillDownConsumed?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingDrillDown?.nonce]);

  const handleSearchSubmit = (e: React.FormEvent) => { e.preventDefault(); loadCalls(0); };

  const clearFilters = () => {
    setDateFrom(''); setDateTo(''); setPhrase(''); setToneCliente(''); setToneAtendente('');
    setCategoria(''); setCriticidade(''); setFindingType('');
    setAgentName(''); setDirection(''); setSkill(''); setDurationMin(''); setDurationMax('');
    setIsFailedFilter('');
    setCustomerNumberFilter(''); setExtensionFilter(''); setDisconnectedByFilter(''); setHasHoldFilter(false);
    setWrapupTimeMin(''); setWrapupTimeMax(''); setTransferTargetExtension(''); setTransferTargetAgentName('');
    setTargetSwitchCallId('');
    setTimeout(() => loadCalls(0), 0);
  };

  const openDetail = (id: number) => {
    setDetailId(id);
    setDetailLoading(true);
    api.get<InsightsDetailResponse>(`/insights/calls/${id}`)
      .then(r => setDetail(r.data))
      .catch(err => {
        console.error('Erro ao carregar detalhe da chamada:', err);
        alert('Erro ao carregar detalhe da chamada.');
        setDetailId(null);
      })
      .finally(() => setDetailLoading(false));
  };

  const closeDetail = () => { setDetailId(null); setDetail(null); };

  const findingsByTipo = (tipo: string) => detail?.findings.filter(f => f.tipo === tipo) ?? [];

  return (
    <>
      {/* Modal de detalhe */}
      {detailId !== null && (
        <div className="modal-overlay" onClick={closeDetail}>
          <div className="modal" style={{ maxWidth: 760, width: '96vw' }} onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h3 style={{ fontSize: '1rem', fontWeight: 600 }}>
                Chamada {detail?.audioFile.callRef ?? detailId}
              </h3>
              <button className="btn-close" onClick={closeDetail}>×</button>
            </div>
            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              {detailLoading || !detail ? (
                <div className="loading-state"><div className="spinner" />Carregando…</div>
              ) : (
                <>
                  {/* Metadados */}
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Data/Hora</div>
                      <div style={{ fontSize: '.9rem' }}>{formatDate(detail.audioFile.callStarttime)}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Duração</div>
                      <div className="mono" style={{ fontSize: '.9rem' }}>{formatDuration(detail.audioFile.durationSeconds)}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Direção</div>
                      <div>{directionBadge(detail.audioFile.direction)}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Atendente</div>
                      <div style={{ fontSize: '.9rem' }}>{detail.audioFile.agentName || '—'}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Fila/Departamento</div>
                      <div style={{ fontSize: '.9rem' }}>{detail.audioFile.skill || '—'}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 2 }}>Criticidade</div>
                      <div>{criticidadeBadge(detail.insights?.criticidade)}</div>
                    </div>
                  </div>

                  {/* Identificação (campos que não viraram coluna/filtro — decisão 8) */}
                  <div>
                    <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Identificação</div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Organização</div>
                        <div style={{ fontSize: '.85rem' }}>{detail.audioFile.organization || '—'}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>DNIS</div>
                        <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.dnis || '—'}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>ANI</div>
                        <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.ani || '—'}</div>
                      </div>
                    </div>
                  </div>

                  {/* Qualidade (campos que não viraram coluna — decisão 8) */}
                  <div>
                    <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Qualidade</div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Nº de esperas</div>
                        <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.numberOfHolds ?? '—'}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Tempo em espera</div>
                        <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.totalHoldTime != null ? `${detail.audioFile.totalHoldTime}s` : '—'}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Wrap-up</div>
                        <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.wrapupTime != null ? `${detail.audioFile.wrapupTime}s` : '—'}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Nº de transferências</div>
                        <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.numberOfTransfers ?? '—'}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Nº de conferências</div>
                        <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.numberOfConferences ?? '—'}</div>
                      </div>
                    </div>
                  </div>

                  {/* Histórico de transferências (grupo D) */}
                  <div>
                    <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Histórico de transferências</div>
                    {detail.transferEvents.length === 0 ? (
                      <div style={{ fontSize: '.85rem', color: 'var(--text-muted)', fontStyle: 'italic' }}>Sem transferências nesta chamada.</div>
                    ) : (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                        {detail.transferEvents.map(ev => (
                          <div key={ev.order} style={{
                            background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                            borderRadius: 8, padding: '8px 12px', display: 'flex', alignItems: 'center', gap: 12,
                          }}>
                            <span className="mono" style={{ fontSize: '.78rem', color: 'var(--text-muted)' }}>{formatDate(ev.transferredAt)}</span>
                            {disconnectedByBadge(ev.disconnectedBy)}
                            <span style={{ fontSize: '.85rem', flex: 1 }}>
                              {ev.resolved
                                ? `${ev.targetExtension ?? '—'}${ev.targetAgentName ? ` (${ev.targetAgentName})` : ''}`
                                : <span className="badge badge-gray">Não identificado</span>}
                            </span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Técnico/Auditoria — só ADMIN (grupo C) */}
                  {isAdmin && (
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>🔒 Técnico / Auditoria (admin)</div>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
                        <div>
                          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Codec</div>
                          <div style={{ fontSize: '.85rem' }}>{detail.audioFile.codec || '—'}</div>
                        </div>
                        <div>
                          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Pacotes RTP perdidos</div>
                          <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.missedRtpPackets ?? '—'}</div>
                        </div>
                        <div>
                          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Erros de decodificação</div>
                          <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.decodingErrors ?? '—'}</div>
                        </div>
                        <div>
                          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>ID global (switch)</div>
                          <div className="mono" style={{ fontSize: '.85rem' }}>{detail.audioFile.switchCallId || '—'}</div>
                        </div>
                        <div>
                          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Tronco</div>
                          <div style={{ fontSize: '.85rem' }}>{detail.audioFile.trunk || '—'}</div>
                        </div>
                        <div>
                          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Tipo de captura</div>
                          <div style={{ fontSize: '.85rem' }}>{detail.audioFile.captureType || '—'}</div>
                        </div>
                        <div>
                          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)' }}>Datasource</div>
                          <div style={{ fontSize: '.85rem' }}>{detail.audioFile.datasourceName || '—'}</div>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Player */}
                  <div>
                    <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Gravação da chamada</div>
                    <AuthedAudio path={`/insights/calls/${detailId}/audio`} style={{ width: '100%', height: 36 }} />
                  </div>

                  {/* Resumo / Insights */}
                  {detail.insights && (
                    <div>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Resumo (IA)</div>
                      <div style={{
                        background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                        borderRadius: 8, padding: '10px 12px', fontSize: '.85rem',
                      }}>
                        {detail.insights.resumo || '—'}
                      </div>
                      <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
                        {detail.insights.categoriaAssunto && <span className="chip">{detail.insights.categoriaAssunto}</span>}
                        {detail.insights.sentimentoGeral && <span className="chip">Sentimento: {detail.insights.sentimentoGeral}</span>}
                        {detail.insights.aderenciaScript != null && (
                          <span className="chip">Aderência ao script: {Math.round(detail.insights.aderenciaScript * 100)}%</span>
                        )}
                      </div>
                    </div>
                  )}

                  {/* Avaliação (Fase 1 do Quality Management) */}
                  {detail.evaluation && (
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                        <span style={{ fontSize: '.75rem', color: 'var(--text-muted)' }}>Avaliação</span>
                        <span className="chip">Nota: {detail.evaluation.notaTotal.toFixed(1)}</span>
                        {detail.evaluation.isFailed && <span className="badge badge-danger">Reprovada</span>}
                      </div>
                      {detail.evaluation.isFailed && detail.evaluation.failReason && (
                        <div style={{ fontSize: '.8rem', color: 'var(--text-muted)', fontStyle: 'italic', marginBottom: 8 }}>
                          {detail.evaluation.failReason}
                        </div>
                      )}
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                        {detail.evaluationItems.map(item => (
                          <div key={item.id} style={{
                            background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                            borderRadius: 8, padding: '8px 12px',
                          }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                              <span style={{ fontSize: '.85rem' }}>{item.justificativa || '—'}</span>
                              <span className="badge badge-info" style={{ flexShrink: 0 }}>{item.nota}</span>
                            </div>
                            {item.trechoReferencia && (
                              <div style={{ fontSize: '.78rem', color: 'var(--text-muted)', fontStyle: 'italic', marginTop: 4 }}>
                                "{item.trechoReferencia}"
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Achados */}
                  {(['falha', 'melhoria', 'treinamento', 'tendencia'] as const).map(tipo => {
                    const list = findingsByTipo(tipo);
                    if (list.length === 0) return null;
                    return (
                      <div key={tipo}>
                        <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>{FINDING_LABELS[tipo]}</div>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                          {list.map(f => (
                            <div key={f.id} style={{
                              background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                              borderRadius: 8, padding: '8px 12px',
                            }}>
                              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                                <span style={{ fontSize: '.85rem' }}>{f.descricao}</span>
                                <span className={`badge ${f.prioridade === 'alta' ? 'badge-danger' : f.prioridade === 'media' ? 'badge-warning' : 'badge-success'}`} style={{ flexShrink: 0 }}>
                                  {f.prioridade}
                                </span>
                              </div>
                              {f.trechoReferencia && (
                                <div style={{ fontSize: '.78rem', color: 'var(--text-muted)', fontStyle: 'italic', marginTop: 4 }}>
                                  "{f.trechoReferencia}"
                                </div>
                              )}
                            </div>
                          ))}
                        </div>
                      </div>
                    );
                  })}

                  {/* Transcrição */}
                  <div>
                    <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 6 }}>Transcrição</div>
                    <div style={{
                      background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                      borderRadius: 8, padding: '12px 14px', maxHeight: 320, overflowY: 'auto',
                      display: 'flex', flexDirection: 'column', gap: 10,
                    }}>
                      {detail.segments.length === 0 ? (
                        <span className="text-muted" style={{ fontSize: '.85rem' }}>Transcrição não disponível</span>
                      ) : detail.segments.map(seg => (
                        <div key={seg.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                            {speakerBadge(seg.speaker)}
                            {toneBadge('Tom (texto)', seg.toneSemantic)}
                            {toneBadge('Tom (voz)', seg.toneAcoustic)}
                          </div>
                          <span style={{ fontSize: '.85rem', wordBreak: 'break-word' }}>{seg.text}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </>
              )}
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={closeDetail}>Fechar</button>
            </div>
          </div>
        </div>
      )}

      {/* Toolbar */}
      <div className="toolbar">
        <form className="toolbar-left" onSubmit={handleSearchSubmit}>
          <div className="search-wrapper">
            <span className="search-icon">🔍</span>
            <input className="search-input" aria-label="Buscar por texto na transcrição" placeholder="Buscar por texto na transcrição..."
              value={text} onChange={e => setText(e.target.value)} />
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
      </div>

      {filtersOpen && (
        <div className="form-grid" style={{ gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 20 }}>
          <div>
            <label className="form-label">Data de</label>
            <input type="date" className="form-input" value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Data até</label>
            <input type="date" className="form-input" value={dateTo} onChange={e => setDateTo(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Frase exata</label>
            <input className="form-input" placeholder='ex: "cancelar o pedido"' value={phrase} onChange={e => setPhrase(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Tom do cliente</label>
            <select className="form-select" value={toneCliente} onChange={e => setToneCliente(e.target.value)}>
              <option value="">Qualquer</option>
              {TONE_OPTIONS.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div>
            <label className="form-label">Tom do atendente</label>
            <select className="form-select" value={toneAtendente} onChange={e => setToneAtendente(e.target.value)}>
              <option value="">Qualquer</option>
              {TONE_OPTIONS.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div>
            <label className="form-label">Categoria/Assunto</label>
            <input className="form-input" placeholder="ex: Cobrança" value={categoria} onChange={e => setCategoria(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Criticidade</label>
            <select className="form-select" value={criticidade} onChange={e => setCriticidade(e.target.value)}>
              <option value="">Qualquer</option>
              {CRITICIDADE_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div>
            <label className="form-label">Tipo de achado</label>
            <select className="form-select" value={findingType} onChange={e => setFindingType(e.target.value)}>
              <option value="">Qualquer</option>
              {FINDING_TYPE_OPTIONS.map(t => <option key={t} value={t}>{FINDING_LABELS[t]}</option>)}
            </select>
          </div>
          <div>
            <label className="form-label">Atendente</label>
            <input className="form-input" placeholder="ex: Luana Rangel" value={agentName} onChange={e => setAgentName(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Direção</label>
            <select className="form-select" value={direction} onChange={e => setDirection(e.target.value)}>
              <option value="">Qualquer</option>
              <option value="inbound">Recebida</option>
              <option value="outbound">Efetuada</option>
            </select>
          </div>
          <div>
            <label className="form-label">Fila</label>
            <input className="form-input" placeholder="ex: BPO Alfa SAC" value={skill} onChange={e => setSkill(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Duração mínima (seg)</label>
            <input type="number" min={0} className="form-input" placeholder="ex: 60" value={durationMin} onChange={e => setDurationMin(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Duração máxima (seg)</label>
            <input type="number" min={0} className="form-input" placeholder="ex: 600" value={durationMax} onChange={e => setDurationMax(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Avaliação</label>
            <select className="form-select" value={isFailedFilter} onChange={e => setIsFailedFilter(e.target.value)}>
              <option value="">Qualquer</option>
              <option value="true">Reprovadas</option>
              <option value="false">Aprovadas</option>
            </select>
          </div>
          <div>
            <label className="form-label">Nº do cliente</label>
            <input className="form-input" placeholder="ex: 11 98421-7734" value={customerNumberFilter} onChange={e => setCustomerNumberFilter(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Ramal</label>
            <input className="form-input" placeholder="ex: 4021" value={extensionFilter} onChange={e => setExtensionFilter(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Quem desligou</label>
            <select className="form-select" value={disconnectedByFilter} onChange={e => setDisconnectedByFilter(e.target.value)}>
              <option value="">Qualquer</option>
              <option value="atendente">Atendente</option>
              <option value="cliente">Cliente</option>
            </select>
          </div>
          <div style={{ display: 'flex', alignItems: 'flex-end', paddingBottom: 6 }}>
            <label className="form-label" style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 0 }}>
              <input type="checkbox" checked={hasHoldFilter} onChange={e => setHasHoldFilter(e.target.checked)} />
              Teve espera
            </label>
          </div>
          <div>
            <label className="form-label">Wrap-up mínimo (seg)</label>
            <input type="number" min={0} className="form-input" placeholder="ex: 0" value={wrapupTimeMin} onChange={e => setWrapupTimeMin(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Wrap-up máximo (seg)</label>
            <input type="number" min={0} className="form-input" placeholder="ex: 60" value={wrapupTimeMax} onChange={e => setWrapupTimeMax(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Ramal destino (transferência)</label>
            <input className="form-input" placeholder="ex: 4108" value={transferTargetExtension} onChange={e => setTransferTargetExtension(e.target.value)} />
          </div>
          <div>
            <label className="form-label">Atendente destino (transferência)</label>
            <input className="form-input" placeholder="ex: Diego Ramalho" value={transferTargetAgentName} onChange={e => setTransferTargetAgentName(e.target.value)} />
          </div>
          {isAdmin && (
            <div>
              <label className="form-label">🔒 ID global (switch_call_id)</label>
              <input className="form-input mono" placeholder="ex: SW-88213371" value={targetSwitchCallId} onChange={e => setTargetSwitchCallId(e.target.value)} />
            </div>
          )}
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
                <th>Data/Hora</th>
                <th>Atendente</th>
                <th>Direção</th>
                <th>Fila</th>
                <th>Duração</th>
                <th>Categoria</th>
                <th>Sentimento</th>
                <th>Criticidade</th>
                <th>Nota</th>
                <th>Status</th>
                <th>Nº do cliente</th>
                <th>Ramal</th>
                <th>ANI</th>
                <th>Quem desligou</th>
                <th>Ramal destino</th>
                <th>Atendente destino</th>
              </tr>
            </thead>
            <tbody>
              {items.length === 0 ? (
                <tr><td colSpan={16} className="table-empty">Nenhuma chamada encontrada</td></tr>
              ) : items.map(item => (
                <tr key={item.id} onClick={() => openDetail(item.id)} style={{ cursor: 'pointer' }} title="Clique para ver detalhes">
                  <td className="td-muted">{formatDate(item.callStarttime)}</td>
                  <td>{item.agentName ? toTitleCase(item.agentName) : <span className="text-muted">—</span>}</td>
                  <td>{directionBadge(item.direction)}</td>
                  <td className="td-muted">{item.skill || '—'}</td>
                  <td className="mono">{formatDuration(item.durationSeconds)}</td>
                  <td>{item.categoriaAssunto || <span className="text-muted">—</span>}</td>
                  <td>{item.sentimentoGeral || <span className="text-muted">—</span>}</td>
                  <td>{criticidadeBadge(item.criticidade)}</td>
                  <td>
                    {item.notaTotal != null ? (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <span className="mono">{item.notaTotal.toFixed(1)}</span>
                        {item.isFailed && <span className="badge badge-danger">Reprovada</span>}
                      </div>
                    ) : <span className="text-muted">—</span>}
                  </td>
                  <td><span className="badge badge-info">{item.status}</span></td>
                  <td className="mono td-muted">{item.customerNumber || '—'}</td>
                  <td className="mono td-muted">{item.extension || '—'}</td>
                  <td className="mono td-muted">{item.ani || '—'}</td>
                  <td>{disconnectedByBadge(item.disconnectedBy)}</td>
                  <td>{transferTargetCell(item.numberOfTransfers, item.transferTargetExtension)}</td>
                  <td>{transferTargetCell(item.numberOfTransfers, item.transferTargetAgentName)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pagination">
            <span className="pagination-info">{items.length} registros nesta página</span>
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
  );
}
