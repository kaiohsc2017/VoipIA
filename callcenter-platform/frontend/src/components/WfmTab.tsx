import { useEffect, useState, useMemo, useRef } from 'react';
import {
  TrendingUp,
  AlertTriangle,
  Users,
  Clock,
  CheckCircle2,
  RefreshCw,
  Calculator,
  Layers,
  ShieldAlert,
  Calendar,
  Pencil,
  Trash2,
} from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type { CcAgent } from '../api/types';

interface Queue {
  id: number;
  name: string;
  strategy?: string;
  timeoutSeconds?: number;
}

interface WfmForecastDto {
  id: number;
  queueId: number;
  queueName: string;
  forecastTimestamp: string;
  intervalMinutes: number;
  predictedCallVolume: number;
  predictedAhtSeconds: number;
  requiredAgents: number;
  currentScheduledAgents: number;
  predictedSlaPercent: number;
  targetSlaPercent: number;
  slaBreachRisk: boolean;
  algorithm: string;
  createdAt: string;
}

interface AgentSchedule {
  id: number;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  active: boolean;
}

const DAY_NAMES: Record<number, string> = {
  1: 'Segunda',
  2: 'Terça',
  3: 'Quarta',
  4: 'Quinta',
  5: 'Sexta',
  6: 'Sábado',
  7: 'Domingo',
};

const DAY_FULL_NAMES: Record<number, string> = {
  1: 'Segunda-feira',
  2: 'Terça-feira',
  3: 'Quarta-feira',
  4: 'Quinta-feira',
  5: 'Sexta-feira',
  6: 'Sábado',
  7: 'Domingo',
};

export function WfmTab() {
  const [viewMode, setViewMode] = useState<'dimensionamento' | 'escalas'>('dimensionamento');
  const [queues, setQueues] = useState<Queue[]>([]);
  const [selectedQueueId, setSelectedQueueId] = useState<number | null>(null);
  const [forecasts, setForecasts] = useState<WfmForecastDto[]>([]);
  const [alerts, setAlerts] = useState<WfmForecastDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Escalas da Equipe
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [agentSchedules, setAgentSchedules] = useState<Record<number, AgentSchedule[]>>({});
  const [loadingSchedules, setLoadingSchedules] = useState(false);
  const [selectedAgentForModal, setSelectedAgentForModal] = useState<CcAgent | null>(null);

  // Simulador de Cenários Rápidos (Erlang-C Interativo)
  const [simCallsPerHour, setSimCallsPerHour] = useState<number>(60);
  const [simAhtSec, setSimAhtSec] = useState<number>(180);
  const [simTargetSla, setSimTargetSla] = useState<number>(80);
  const [simTargetTime, setSimTargetTime] = useState<number>(20);
  const [simAgents, setSimAgents] = useState<number>(5);

  // Flag de montagem — evita setState em componente desmontado (ex: troca de aba antes da
  // resposta chegar), mesmo padrão de DesktopAgenteTab.tsx.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  // Descarta a resposta de uma previsão antiga que chegue depois de uma mais nova (fila trocada,
  // ou "Recalcular Horizonte" clicado, antes da request anterior voltar).
  const forecastSeqRef = useRef(0);

  // Carrega filas e alertas
  useEffect(() => {
    api.get<Queue[]>('/callcenter/filas')
      .then(res => {
        if (!mountedRef.current) return;
        const list = res.data || [];
        setQueues(list);
        if (list.length > 0) {
          setSelectedQueueId(list[0].id);
        }
      })
      .catch(err => {
        console.error('Erro ao carregar filas para WFM:', err);
      });

    api.get<WfmForecastDto[]>('/callcenter/wfm/alerts')
      .then(res => { if (mountedRef.current) setAlerts(res.data || []); })
      .catch(err => console.error('Erro ao buscar alertas WFM:', err));
  }, []);

  // Carrega previsão da fila selecionada
  useEffect(() => {
    if (!selectedQueueId || viewMode !== 'dimensionamento') return;
    loadForecast(selectedQueueId);
  }, [selectedQueueId, viewMode]);

  // Carrega agentes e escalas quando muda para o modo 'escalas'
  useEffect(() => {
    if (viewMode === 'escalas') {
      loadTeamSchedules();
    }
  }, [viewMode]);

  const loadForecast = async (queueId: number) => {
    const seq = ++forecastSeqRef.current;
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<WfmForecastDto[]>(`/callcenter/wfm/queues/${queueId}/predictive`);
      if (!mountedRef.current || seq !== forecastSeqRef.current) return;
      setForecasts(res.data || []);
    } catch (err: any) {
      if (!mountedRef.current || seq !== forecastSeqRef.current) return;
      setError(err?.response?.data?.message || 'Falha ao carregar dados preditivos de WFM.');
    } finally {
      if (mountedRef.current && seq === forecastSeqRef.current) setLoading(false);
    }
  };

  const handleGenerateForecast = async () => {
    if (!selectedQueueId) return;
    const seq = ++forecastSeqRef.current;
    setGenerating(true);
    setError(null);
    try {
      const res = await api.post<WfmForecastDto[]>(`/callcenter/wfm/queues/${selectedQueueId}/predictive/generate?horizonMinutes=60`);
      if (!mountedRef.current || seq !== forecastSeqRef.current) return;
      setForecasts(res.data || []);
    } catch (err: any) {
      if (!mountedRef.current || seq !== forecastSeqRef.current) return;
      setError(err?.response?.data?.message || 'Falha ao gerar nova previsão WFM.');
    } finally {
      if (mountedRef.current && seq === forecastSeqRef.current) setGenerating(false);
    }
  };

  const loadTeamSchedules = async () => {
    setLoadingSchedules(true);
    try {
      const agentsRes = await api.get<CcAgent[]>('/callcenter/agentes');
      if (!mountedRef.current) return;
      const agentList = agentsRes.data || [];
      setAgents(agentList);

      const schedulesMap: Record<number, AgentSchedule[]> = {};
      if (agentList.length > 0) {
        // Uma única requisição em lote (achado de auditoria — antes era 1 requisição por
        // agente via Promise.all, uma rajada de centenas de chamadas HTTP simultâneas na
        // escala de agentes já projetada pelo CLAUDE.md).
        const params = new URLSearchParams();
        agentList.forEach(agent => params.append('agentIds', String(agent.id)));
        const batchRes = await api.get<Record<string, AgentSchedule[]>>(
          `/callcenter/reports/agent-schedules/batch?${params}`
        );
        for (const agent of agentList) {
          schedulesMap[agent.id] = batchRes.data[String(agent.id)] || [];
        }
      }
      if (mountedRef.current) setAgentSchedules(schedulesMap);
    } catch (err) {
      console.error('Erro ao carregar escalas da equipe:', err);
    } finally {
      if (mountedRef.current) setLoadingSchedules(false);
    }
  };

  // Converte o valor bruto do input numérico, tratando string vazia/inválida como 0 — evita
  // "NaN" visível nos KPIs do simulador enquanto o campo está sendo editado.
  const parseSimInput = (raw: string): number => {
    const v = Number(raw);
    return Number.isFinite(v) ? v : 0;
  };

  // Cálculo de simulação local Erlang-C
  const simResult = useMemo(() => {
    const lambda = simCallsPerHour / 3600.0; // chamadas por segundo
    const u = simAhtSec;
    const trafficIntensity = lambda * u; // A em Erlangs

    if (trafficIntensity <= 0) {
      return { erlangs: '0.00', pw: '0.0', sla: '100.0', minAgents: 1 };
    }

    const minAgents = Math.max(1, Math.ceil(trafficIntensity) + 1);
    const m = Math.max(minAgents, simAgents);

    let sumTerms = 0.0;
    let currentTerm = 1.0;
    sumTerms += currentTerm;

    for (let k = 1; k < m; k++) {
      currentTerm *= trafficIntensity / k;
      sumTerms += currentTerm;
    }

    const termM = currentTerm * (trafficIntensity / m);
    const denominatorFactor = m / (m - trafficIntensity);
    const erlangNumerator = termM * denominatorFactor;
    const erlangDenominator = sumTerms + erlangNumerator;

    let pw = erlangNumerator / erlangDenominator;
    if (pw > 1.0) pw = 1.0;
    if (pw < 0.0 || isNaN(pw)) pw = 0.0;

    const exponent = -((m - trafficIntensity) * (simTargetTime / simAhtSec));
    let sla = 1.0 - (pw * Math.exp(exponent));
    sla = Math.max(0.0, Math.min(1.0, sla)) * 100.0;

    return {
      erlangs: trafficIntensity.toFixed(2),
      pw: (pw * 100).toFixed(1),
      sla: sla.toFixed(1),
      minAgents,
    };
  }, [simCallsPerHour, simAhtSec, simTargetTime, simAgents]);

  const selectedQueue = queues.find(q => q.id === selectedQueueId);

  const avgPredictedVolume = forecasts.reduce((acc, cur) => acc + cur.predictedCallVolume, 0);
  const avgRequiredAgents = forecasts.length > 0
    ? Math.round(forecasts.reduce((acc, cur) => acc + cur.requiredAgents, 0) / forecasts.length)
    : 0;
  const currentAgents = forecasts.length > 0 ? forecasts[0].currentScheduledAgents : 0;
  const hasRisk = forecasts.some(f => f.slaBreachRisk);

  return (
    <div className="tab-content" style={{ padding: '24px', maxWidth: '1400px', margin: '0 auto' }}>
      {/* Header com Toggle de Visão */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)', margin: 0, display: 'flex', alignItems: 'center', gap: '10px' }}>
            <TrendingUp size={26} color="var(--clr-primary, #1e40af)" />
            WFM & Dimensionamento de Filas
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', margin: '4px 0 0 0' }}>
            Planejamento preditivo de capacidade, escalas semanais e aderência de operadores.
          </p>
        </div>

        {/* View Mode Switcher */}
        <div style={{ display: 'flex', background: 'var(--bg-glass, rgba(0,0,0,0.04))', padding: '4px', borderRadius: '10px', border: '1px solid var(--border-glass, #e5e7eb)', gap: '4px' }}>
          <button
            type="button"
            className={`btn btn-sm ${viewMode === 'dimensionamento' ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setViewMode('dimensionamento')}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '0.82rem', fontWeight: 600 }}
          >
            <TrendingUp size={15} />
            Dimensionamento Preditivo
          </button>
          <button
            type="button"
            className={`btn btn-sm ${viewMode === 'escalas' ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setViewMode('escalas')}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '0.82rem', fontWeight: 600 }}
          >
            <Calendar size={15} />
            Escalas & Jornadas da Equipe
          </button>
        </div>
      </div>

      {/* ─── VISÃO 1: DIMENSIONAMENTO PREDITIVO ERLANG-C ─── */}
      {viewMode === 'dimensionamento' && (
        <>
          <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', marginBottom: '20px', gap: '12px' }}>
            <select
              value={selectedQueueId ?? ''}
              onChange={e => setSelectedQueueId(Number(e.target.value))}
              style={{
                padding: '8px 14px',
                borderRadius: '8px',
                border: '1px solid var(--border-glass, #e5e7eb)',
                backgroundColor: 'var(--bg-card, #ffffff)',
                color: 'var(--text-primary)',
                fontSize: '0.875rem',
                fontWeight: 500,
              }}
            >
              {queues.map(q => (
                <option key={q.id} value={q.id}>
                  Fila: {q.name}
                </option>
              ))}
            </select>

            <button
              className="btn btn-primary"
              onClick={handleGenerateForecast}
              disabled={generating || !selectedQueueId}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                padding: '8px 16px',
                borderRadius: '8px',
                fontWeight: 600,
              }}
            >
              <RefreshCw size={16} className={generating ? 'spin' : ''} />
              {generating ? 'Calculando...' : 'Recalcular Horizonte'}
            </button>
          </div>

          {/* Alertas de Risco */}
          {alerts.length > 0 && (
            <div style={{
              backgroundColor: '#fef2f2',
              border: '1px solid #f87171',
              borderRadius: '10px',
              padding: '14px 18px',
              marginBottom: '24px',
              display: 'flex',
              alignItems: 'center',
              gap: '14px',
              color: '#991b1b',
            }}>
              <ShieldAlert size={22} style={{ flexShrink: 0 }} />
              <div>
                <strong>Atenção de SLA:</strong> Foram detectados {alerts.length} intervalos com risco iminente de não atingimento da meta de SLA.
              </div>
            </div>
          )}

          {error && (
            <div style={{
              backgroundColor: '#fee2e2',
              border: '1px solid #ef4444',
              borderRadius: '10px',
              padding: '12px 16px',
              marginBottom: '20px',
              color: '#b91c1c',
              fontSize: '0.875rem',
            }}>
              {error}
            </div>
          )}

          {/* KPI Cards */}
          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
            gap: '16px',
            marginBottom: '24px',
          }}>
            <div style={{ background: 'var(--bg-card, #ffffff)', border: '1px solid var(--border-glass, #e5e7eb)', borderRadius: '12px', padding: '18px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', fontSize: '0.8rem', fontWeight: 600 }}>
                <span>FILA EM ANÁLISE</span>
                <Layers size={18} color="var(--clr-primary, #1e40af)" />
              </div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                {selectedQueue?.name || 'Nenhuma'}
              </div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                Estratégia: {selectedQueue?.strategy || 'rrmemory'}
              </div>
            </div>

            <div style={{ background: 'var(--bg-card, #ffffff)', border: '1px solid var(--border-glass, #e5e7eb)', borderRadius: '12px', padding: '18px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', fontSize: '0.8rem', fontWeight: 600 }}>
                <span>VOLUME TOTAL ESTIMADO</span>
                <Clock size={18} color="var(--clr-cyan, #0284c7)" />
              </div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                {avgPredictedVolume} chamadas
              </div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                Horizonte de 60 minutos
              </div>
            </div>

            <div style={{ background: 'var(--bg-card, #ffffff)', border: '1px solid var(--border-glass, #e5e7eb)', borderRadius: '12px', padding: '18px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', fontSize: '0.8rem', fontWeight: 600 }}>
                <span>AGENTES RECOMENDADOS</span>
                <Users size={18} color="var(--clr-accent, #3b82f6)" />
              </div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                {avgRequiredAgents} agentes
              </div>
              <div style={{ fontSize: '0.75rem', color: currentAgents >= avgRequiredAgents ? 'var(--clr-success, #059669)' : 'var(--clr-danger, #dc2626)', fontWeight: 600 }}>
                Escalados atualmente: {currentAgents} {currentAgents >= avgRequiredAgents ? '✓ Adequado' : '⚠ Déficit'}
              </div>
            </div>

            <div style={{ background: 'var(--bg-card, #ffffff)', border: '1px solid var(--border-glass, #e5e7eb)', borderRadius: '12px', padding: '18px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', fontSize: '0.8rem', fontWeight: 600 }}>
                <span>STATUS DE CONFORMIDADE</span>
                {hasRisk ? <AlertTriangle size={18} color="#dc2626" /> : <CheckCircle2 size={18} color="#059669" />}
              </div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: hasRisk ? '#dc2626' : '#059669' }}>
                {hasRisk ? 'Risco de Estouro' : 'SLA Garantido'}
              </div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                Meta contratual: 80% em 20s
              </div>
            </div>
          </div>

          {/* Grid: Tabela de Previsão por Intervalo & Simulador Erlang-C */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(450px, 1fr))', gap: '24px' }}>
            <div style={{ background: 'var(--bg-card, #ffffff)', border: '1px solid var(--border-glass, #e5e7eb)', borderRadius: '12px', padding: '20px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <h3 style={{ fontSize: '1rem', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>
                  Horizonte de Demanda por Intervalo
                </h3>
                <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                  Algoritmo: Erlang-C + EWMA
                </span>
              </div>

              {loading ? (
                <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
                  <RefreshCw size={24} className="spin" style={{ margin: '0 auto 8px' }} />
                  Carregando dados preditivos...
                </div>
              ) : forecasts.length === 0 ? (
                <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)' }}>
                  Nenhum dado preditivo disponível para esta fila.
                </div>
              ) : (
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.82rem' }}>
                    <thead>
                      <tr style={{ borderBottom: '2px solid var(--border-glass, #e5e7eb)', textAlign: 'left', color: 'var(--text-muted)' }}>
                        <th style={{ padding: '8px' }}>Horário</th>
                        <th style={{ padding: '8px' }}>Vol. Previsto</th>
                        <th style={{ padding: '8px' }}>TMA (s)</th>
                        <th style={{ padding: '8px' }}>Agentes Nec.</th>
                        <th style={{ padding: '8px' }}>SLA Estimado</th>
                        <th style={{ padding: '8px' }}>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {forecasts.map(f => {
                        const time = new Date(f.forecastTimestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
                        return (
                          <tr key={f.id} style={{ borderBottom: '1px solid var(--border-glass, #e5e7eb)' }}>
                            <td style={{ padding: '10px 8px', fontWeight: 600 }}>{time}</td>
                            <td style={{ padding: '10px 8px' }}>{f.predictedCallVolume} chamadas</td>
                            <td style={{ padding: '10px 8px' }}>{f.predictedAhtSeconds}s</td>
                            <td style={{ padding: '10px 8px', fontWeight: 700 }}>
                              {f.requiredAgents} <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>({f.currentScheduledAgents} na escala)</span>
                            </td>
                            <td style={{ padding: '10px 8px', fontWeight: 600 }}>
                              {f.predictedSlaPercent?.toFixed(1)}%
                            </td>
                            <td style={{ padding: '10px 8px' }}>
                              {f.slaBreachRisk ? (
                                <span style={{ backgroundColor: '#fee2e2', color: '#dc2626', padding: '2px 8px', borderRadius: '4px', fontSize: '0.72rem', fontWeight: 700 }}>
                                  Risco SLA
                                </span>
                              ) : (
                                <span style={{ backgroundColor: '#d1fae5', color: '#059669', padding: '2px 8px', borderRadius: '4px', fontSize: '0.72rem', fontWeight: 700 }}>
                                  OK
                                </span>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* Simulador Interativo Erlang-C */}
            <div style={{ background: 'var(--bg-card, #ffffff)', border: '1px solid var(--border-glass, #e5e7eb)', borderRadius: '12px', padding: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
                <Calculator size={20} color="var(--clr-primary, #1e40af)" />
                <h3 style={{ fontSize: '1rem', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>
                  Simulador Matemático Erlang-C (What-If)
                </h3>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginBottom: '16px' }}>
                <div>
                  <label htmlFor="sim-calls-per-hour" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                    Chamadas / Hora (λ)
                  </label>
                  <input
                    id="sim-calls-per-hour"
                    type="number"
                    value={simCallsPerHour}
                    onChange={e => setSimCallsPerHour(parseSimInput(e.target.value))}
                    style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
                  />
                </div>
                <div>
                  <label htmlFor="sim-aht-sec" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                    TMA em Segundos (AHT)
                  </label>
                  <input
                    id="sim-aht-sec"
                    type="number"
                    value={simAhtSec}
                    onChange={e => setSimAhtSec(parseSimInput(e.target.value))}
                    style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
                  />
                </div>
                <div>
                  <label htmlFor="sim-target-sla" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                    Meta SLA (%)
                  </label>
                  <input
                    id="sim-target-sla"
                    type="number"
                    value={simTargetSla}
                    onChange={e => setSimTargetSla(parseSimInput(e.target.value))}
                    style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
                  />
                </div>
                <div>
                  <label htmlFor="sim-target-time" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                    Tempo Alvo Espera (s)
                  </label>
                  <input
                    id="sim-target-time"
                    type="number"
                    value={simTargetTime}
                    onChange={e => setSimTargetTime(parseSimInput(e.target.value))}
                    style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
                  />
                </div>
              </div>

              <div style={{ marginBottom: '16px' }}>
                <label htmlFor="sim-agents" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                  Agentes Disponíveis no Cenário ({simAgents})
                </label>
                <input
                  id="sim-agents"
                  type="range"
                  min={1}
                  max={30}
                  value={simAgents}
                  onChange={e => setSimAgents(Number(e.target.value))}
                  style={{ width: '100%', accentColor: 'var(--clr-primary, #1e40af)' }}
                />
              </div>

              <div style={{ background: 'var(--bg-deep, #f8f9fa)', borderRadius: '10px', padding: '14px', border: '1px solid var(--border-glass, #e5e7eb)' }}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', textAlign: 'center' }}>
                  <div>
                    <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', fontWeight: 600 }}>TRÁFEGO (ERLANGS)</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: 'var(--text-primary)' }}>{simResult.erlangs} E</div>
                  </div>
                  <div>
                    <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', fontWeight: 600 }}>PROBABILIDADE DE ESPERA</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: 'var(--text-primary)' }}>{simResult.pw}%</div>
                  </div>
                  <div>
                    <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', fontWeight: 600 }}>SLA PROJETADO</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: Number(simResult.sla) >= simTargetSla ? '#059669' : '#dc2626' }}>
                      {simResult.sla}%
                    </div>
                  </div>
                  <div>
                    <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', fontWeight: 600 }}>AGENTES MÍNIMOS</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: 'var(--clr-primary, #1e40af)' }}>{simResult.minAgents}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </>
      )}

      {/* ─── VISÃO 2: GESTÃO DE ESCALAS & JORNADAS DA EQUIPE ─── */}
      {viewMode === 'escalas' && (
        <div style={{ background: 'var(--bg-card, #ffffff)', border: '1px solid var(--border-glass, #e5e7eb)', borderRadius: '12px', padding: '24px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
            <div>
              <h2 style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>
                Quadro Semanal de Escalas dos Operadores
              </h2>
              <p style={{ color: 'var(--text-muted)', fontSize: '0.8rem', margin: '4px 0 0 0' }}>
                Defina os horários programados de login e logout para o cálculo automático de aderência à escala.
              </p>
            </div>

            <button
              className="btn btn-ghost btn-sm"
              onClick={loadTeamSchedules}
              disabled={loadingSchedules}
              style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
            >
              <RefreshCw size={14} className={loadingSchedules ? 'spin' : ''} />
              Atualizar Quadro
            </button>
          </div>

          {loadingSchedules ? (
            <div style={{ padding: '60px', textAlign: 'center', color: 'var(--text-muted)' }}>
              <RefreshCw size={26} className="spin" style={{ margin: '0 auto 12px' }} />
              Carregando escalas da equipe...
            </div>
          ) : agents.length === 0 ? (
            <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
              Nenhum operador cadastrado no sistema.
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.82rem' }}>
                <thead>
                  <tr style={{ borderBottom: '2px solid var(--border-glass, #e5e7eb)', textAlign: 'left', color: 'var(--text-muted)' }}>
                    <th style={{ padding: '10px 8px' }}>Operador</th>
                    <th style={{ padding: '10px 8px' }}>Ramal</th>
                    {[1, 2, 3, 4, 5, 6, 7].map(d => (
                      <th key={d} style={{ padding: '10px 8px', textAlign: 'center' }}>{DAY_NAMES[d]}</th>
                    ))}
                    <th style={{ padding: '10px 8px', textAlign: 'center' }}>Ações</th>
                  </tr>
                </thead>
                <tbody>
                  {agents.map(agent => {
                    const schedules = agentSchedules[agent.id] || [];
                    const scheduleMap = new Map(schedules.map(s => [s.dayOfWeek, s]));

                    return (
                      <tr key={agent.id} style={{ borderBottom: '1px solid var(--border-glass, #e5e7eb)' }}>
                        <td style={{ padding: '12px 8px', fontWeight: 600, color: 'var(--text-primary)' }}>
                          {agent.name}
                        </td>
                        <td style={{ padding: '12px 8px', fontFamily: 'monospace', color: 'var(--text-muted)' }}>
                          {agent.extension?.extension ?? '—'}
                        </td>
                        {[1, 2, 3, 4, 5, 6, 7].map(day => {
                          const sched = scheduleMap.get(day);
                          return (
                            <td key={day} style={{ padding: '12px 8px', textAlign: 'center' }}>
                              {sched ? (
                                <span style={{
                                  display: 'inline-block',
                                  padding: '3px 8px',
                                  borderRadius: '6px',
                                  backgroundColor: 'rgba(30, 64, 175, 0.08)',
                                  color: 'var(--clr-primary, #1e40af)',
                                  fontWeight: 600,
                                  fontSize: '0.72rem',
                                  fontFamily: 'monospace',
                                }}>
                                  {sched.startTime.slice(0, 5)}–{sched.endTime.slice(0, 5)}
                                </span>
                              ) : (
                                <span style={{ color: 'var(--text-muted)', fontSize: '0.72rem', opacity: 0.5 }}>
                                  Folga
                                </span>
                              )}
                            </td>
                          );
                        })}
                        <td style={{ padding: '12px 8px', textAlign: 'center' }}>
                          <button
                            className="btn btn-ghost btn-sm"
                            onClick={() => setSelectedAgentForModal(agent)}
                            title="Editar Escala do Operador"
                            style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '0.75rem', color: 'var(--clr-primary)' }}
                          >
                            <Pencil size={13} />
                            Escala
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Modal de Configuração de Escala do Agente */}
      {selectedAgentForModal && (
        <WfmAgentScheduleModal
          agent={selectedAgentForModal}
          onClose={() => {
            setSelectedAgentForModal(null);
            loadTeamSchedules();
          }}
        />
      )}
    </div>
  );
}

function WfmAgentScheduleModal({ agent, onClose }: { agent: CcAgent; onClose: () => void }) {
  const [schedules, setSchedules] = useState<AgentSchedule[]>([]);
  const [loading, setLoading] = useState(true);
  const [dayOfWeek, setDayOfWeek] = useState(1);
  const [startTime, setStartTime] = useState('08:00');
  const [endTime, setEndTime] = useState('17:00');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    api.get<AgentSchedule[]>(`/callcenter/reports/agent-schedules?agentId=${agent.id}`)
      .then(({ data }) => setSchedules(data || []))
      .catch(err => setError(getErrorMessage(err, 'Erro ao carregar escalas.')))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id]);

  const handleAdd = () => {
    if (!startTime || !endTime) return;
    setSaving(true);
    api.post('/callcenter/reports/agent-schedules', {
      agentId: agent.id,
      dayOfWeek: Number(dayOfWeek),
      startTime: startTime.length === 5 ? `${startTime}:00` : startTime,
      endTime: endTime.length === 5 ? `${endTime}:00` : endTime,
    })
      .then(() => load())
      .catch(err => setError(getErrorMessage(err, 'Erro ao salvar escala.')))
      .finally(() => setSaving(false));
  };

  const handleDelete = (id: number) => {
    api.delete(`/callcenter/reports/agent-schedules/${id}`)
      .then(() => load())
      .catch(err => setError(getErrorMessage(err, 'Erro ao remover escala.')));
  };

  const handleApplyWeekdays = () => {
    setSaving(true);
    const promises = [1, 2, 3, 4, 5].map(d =>
      api.post('/callcenter/reports/agent-schedules', {
        agentId: agent.id,
        dayOfWeek: d,
        startTime: startTime.length === 5 ? `${startTime}:00` : startTime,
        endTime: endTime.length === 5 ? `${endTime}:00` : endTime,
      })
    );
    Promise.all(promises)
      .then(() => load())
      .catch(err => setError(getErrorMessage(err, 'Erro ao replicar escala semanal.')))
      .finally(() => setSaving(false));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal-lg" onClick={e => e.stopPropagation()} style={{ maxWidth: '680px' }}>
        <div className="modal-header">
          <h2>📅 Escala de Trabalho — {agent.name}</h2>
          <button className="btn-close" onClick={onClose} aria-label="Fechar">×</button>
        </div>
        <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', margin: 0 }}>
            Configure os horários esperados de login (entrada) e logout (saída) para cada dia da semana.
          </p>

          {error && (
            <div style={{ backgroundColor: '#fee2e2', color: '#b91c1c', padding: '10px 14px', borderRadius: '8px', fontSize: '0.82rem' }}>
              {error}
            </div>
          )}

          <div style={{ background: 'var(--bg-deep, #f8f9fa)', padding: '14px', borderRadius: '10px', border: '1px solid var(--border-glass, #e5e7eb)' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr 1fr auto', gap: '10px', alignItems: 'end' }}>
              <div>
                <label htmlFor="wfm-schedule-day" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                  Dia da Semana
                </label>
                <select
                  id="wfm-schedule-day"
                  className="form-input"
                  value={dayOfWeek}
                  onChange={e => setDayOfWeek(Number(e.target.value))}
                  style={{ width: '100%' }}
                >
                  {Object.entries(DAY_FULL_NAMES).map(([d, name]) => (
                    <option key={d} value={d}>{name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor="wfm-schedule-start" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                  Login (Entrada)
                </label>
                <input
                  id="wfm-schedule-start"
                  type="time"
                  className="form-input"
                  value={startTime}
                  onChange={e => setStartTime(e.target.value)}
                  style={{ width: '100%' }}
                />
              </div>
              <div>
                <label htmlFor="wfm-schedule-end" style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                  Logout (Saída)
                </label>
                <input
                  id="wfm-schedule-end"
                  type="time"
                  className="form-input"
                  value={endTime}
                  onChange={e => setEndTime(e.target.value)}
                  style={{ width: '100%' }}
                />
              </div>
              <button
                type="button"
                className="btn btn-primary"
                onClick={handleAdd}
                disabled={saving}
                style={{ height: '36px', padding: '0 14px' }}
              >
                {saving ? '...' : '+ Adicionar'}
              </button>
            </div>

            <div style={{ marginTop: '10px', display: 'flex', justifyContent: 'flex-end' }}>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={handleApplyWeekdays}
                disabled={saving}
                style={{ fontSize: '0.75rem', color: 'var(--clr-primary)' }}
              >
                ⚡ Replicar horário para Seg–Sex
              </button>
            </div>
          </div>

          <div className="table-wrapper" style={{ maxHeight: '300px', overflowY: 'auto' }}>
            <table style={{ width: '100%' }}>
              <thead>
                <tr>
                  <th>Dia da Semana</th>
                  <th>Horário Entrada (Login)</th>
                  <th>Horário Saída (Logout)</th>
                  <th>Carga Diária</th>
                  <th style={{ width: '50px' }}></th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={5} style={{ textAlign: 'center', padding: '20px' }}>Carregando escalas...</td></tr>
                ) : schedules.length === 0 ? (
                  <tr><td colSpan={5} style={{ textAlign: 'center', padding: '20px', color: 'var(--text-muted)' }}>Nenhuma escala cadastrada para este agente.</td></tr>
                ) : (
                  [...schedules]
                    .sort((a, b) => a.dayOfWeek - b.dayOfWeek)
                    .map(s => {
                      const [sh, sm] = s.startTime.split(':').map(Number);
                      const [eh, em] = s.endTime.split(':').map(Number);
                      const totalMin = (eh * 60 + em) - (sh * 60 + sm);
                      const hours = Math.floor(totalMin / 60);
                      const mins = totalMin % 60;
                      return (
                        <tr key={s.id}>
                          <td style={{ fontWeight: 600 }}>{DAY_FULL_NAMES[s.dayOfWeek]}</td>
                          <td style={{ fontFamily: 'monospace' }}>{s.startTime.slice(0, 5)}</td>
                          <td style={{ fontFamily: 'monospace' }}>{s.endTime.slice(0, 5)}</td>
                          <td style={{ color: 'var(--text-muted)' }}>{hours}h {mins > 0 ? `${mins}m` : ''}</td>
                          <td>
                            <button
                              className="btn btn-ghost btn-sm"
                              onClick={() => handleDelete(s.id)}
                              title="Remover escala"
                              style={{ color: 'var(--clr-danger)' }}
                            >
                              <Trash2 size={13} />
                            </button>
                          </td>
                        </tr>
                      );
                    })
                )}
              </tbody>
            </table>
          </div>
        </div>
        <div className="modal-footer">
          <button className="btn btn-primary" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}
