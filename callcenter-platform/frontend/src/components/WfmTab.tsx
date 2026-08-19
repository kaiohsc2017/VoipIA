import { useEffect, useState, useMemo } from 'react';
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
} from 'lucide-react';
import api from '../api/client';

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

export function WfmTab() {
  const [queues, setQueues] = useState<Queue[]>([]);
  const [selectedQueueId, setSelectedQueueId] = useState<number | null>(null);
  const [forecasts, setForecasts] = useState<WfmForecastDto[]>([]);
  const [alerts, setAlerts] = useState<WfmForecastDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Simulador de Cenários Rápidos (Erlang-C Interativo)
  const [simCallsPerHour, setSimCallsPerHour] = useState<number>(60);
  const [simAhtSec, setSimAhtSec] = useState<number>(180);
  const [simTargetSla, setSimTargetSla] = useState<number>(80);
  const [simTargetTime, setSimTargetTime] = useState<number>(20);
  const [simAgents, setSimAgents] = useState<number>(5);

  // Carrega filas
  useEffect(() => {
    api.get<Queue[]>('/callcenter/filas')
      .then(res => {
        const list = res.data || [];
        setQueues(list);
        if (list.length > 0) {
          setSelectedQueueId(list[0].id);
        }
      })
      .catch(err => {
        console.error('Erro ao carregar filas para WFM:', err);
      });

    // Carrega alertas globais de SLA breach
    api.get<WfmForecastDto[]>('/callcenter/wfm/alerts')
      .then(res => setAlerts(res.data || []))
      .catch(err => console.error('Erro ao buscar alertas WFM:', err));
  }, []);

  // Carrega previsão da fila selecionada
  useEffect(() => {
    if (!selectedQueueId) return;
    loadForecast(selectedQueueId);
  }, [selectedQueueId]);

  const loadForecast = async (queueId: number) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<WfmForecastDto[]>(`/callcenter/wfm/queues/${queueId}/predictive`);
      setForecasts(res.data || []);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Falha ao carregar dados preditivos de WFM.');
    } finally {
      setLoading(false);
    }
  };

  const handleGenerateForecast = async () => {
    if (!selectedQueueId) return;
    setGenerating(true);
    setError(null);
    try {
      const res = await api.post<WfmForecastDto[]>(`/callcenter/wfm/queues/${selectedQueueId}/predictive/generate?horizonMinutes=60`);
      setForecasts(res.data || []);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Falha ao gerar nova previsão WFM.');
    } finally {
      setGenerating(false);
    }
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

    // Erlang-C formula
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

    // SLA = 1 - (Pw * exp(-(m - A) * (targetTime / AHT)))
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

  // Médias do horizonte previsto
  const avgPredictedVolume = forecasts.reduce((acc, cur) => acc + cur.predictedCallVolume, 0);
  const avgRequiredAgents = forecasts.length > 0
    ? Math.round(forecasts.reduce((acc, cur) => acc + cur.requiredAgents, 0) / forecasts.length)
    : 0;
  const currentAgents = forecasts.length > 0 ? forecasts[0].currentScheduledAgents : 0;
  const hasRisk = forecasts.some(f => f.slaBreachRisk);

  return (
    <div className="tab-content" style={{ padding: '24px', maxWidth: '1400px', margin: '0 auto' }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)', margin: 0, display: 'flex', alignItems: 'center', gap: '10px' }}>
            <TrendingUp size={26} color="var(--clr-primary, #1e40af)" />
            WFM & Dimensionamento Preditivo de Filas
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', margin: '4px 0 0 0' }}>
            Previsão de demanda, intensidade de tráfego (Erlangs) e dimensionamento preditivo de operadores (Erlang-C).
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
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
        {/* Card 1: Fila Selecionada */}
        <div style={{
          background: 'var(--bg-card, #ffffff)',
          border: '1px solid var(--border-glass, #e5e7eb)',
          borderRadius: '12px',
          padding: '18px',
          display: 'flex',
          flexDirection: 'column',
          gap: '8px',
        }}>
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

        {/* Card 2: Volume Projetado */}
        <div style={{
          background: 'var(--bg-card, #ffffff)',
          border: '1px solid var(--border-glass, #e5e7eb)',
          borderRadius: '12px',
          padding: '18px',
          display: 'flex',
          flexDirection: 'column',
          gap: '8px',
        }}>
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

        {/* Card 3: Dimensionamento Recomendado */}
        <div style={{
          background: 'var(--bg-card, #ffffff)',
          border: '1px solid var(--border-glass, #e5e7eb)',
          borderRadius: '12px',
          padding: '18px',
          display: 'flex',
          flexDirection: 'column',
          gap: '8px',
        }}>
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

        {/* Card 4: Risco Geral */}
        <div style={{
          background: 'var(--bg-card, #ffffff)',
          border: '1px solid var(--border-glass, #e5e7eb)',
          borderRadius: '12px',
          padding: '18px',
          display: 'flex',
          flexDirection: 'column',
          gap: '8px',
        }}>
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
        {/* Tabela de Previsão por Intervalos */}
        <div style={{
          background: 'var(--bg-card, #ffffff)',
          border: '1px solid var(--border-glass, #e5e7eb)',
          borderRadius: '12px',
          padding: '20px',
        }}>
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
        <div style={{
          background: 'var(--bg-card, #ffffff)',
          border: '1px solid var(--border-glass, #e5e7eb)',
          borderRadius: '12px',
          padding: '20px',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
            <Calculator size={20} color="var(--clr-primary, #1e40af)" />
            <h3 style={{ fontSize: '1rem', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>
              Simulador Matemático Erlang-C (What-If)
            </h3>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginBottom: '16px' }}>
            <div>
              <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                Chamadas / Hora (λ)
              </label>
              <input
                type="number"
                value={simCallsPerHour}
                onChange={e => setSimCallsPerHour(Number(e.target.value))}
                style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
              />
            </div>
            <div>
              <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                TMA em Segundos (AHT)
              </label>
              <input
                type="number"
                value={simAhtSec}
                onChange={e => setSimAhtSec(Number(e.target.value))}
                style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
              />
            </div>
            <div>
              <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                Meta SLA (%)
              </label>
              <input
                type="number"
                value={simTargetSla}
                onChange={e => setSimTargetSla(Number(e.target.value))}
                style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
              />
            </div>
            <div>
              <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                Tempo Alvo Espera (s)
              </label>
              <input
                type="number"
                value={simTargetTime}
                onChange={e => setSimTargetTime(Number(e.target.value))}
                style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border-glass, #e5e7eb)', fontSize: '0.85rem' }}
              />
            </div>
          </div>

          <div style={{ marginBottom: '16px' }}>
            <label style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
              Agentes Disponíveis no Cenário ({simAgents})
            </label>
            <input
              type="range"
              min={1}
              max={30}
              value={simAgents}
              onChange={e => setSimAgents(Number(e.target.value))}
              style={{ width: '100%', accentColor: 'var(--clr-primary, #1e40af)' }}
            />
          </div>

          {/* Resultado da Simulação */}
          <div style={{
            background: 'var(--bg-deep, #f8f9fa)',
            borderRadius: '10px',
            padding: '14px',
            border: '1px solid var(--border-glass, #e5e7eb)',
          }}>
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
    </div>
  );
}
