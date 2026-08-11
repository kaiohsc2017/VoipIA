import { useEffect, useState } from 'react';
import { PhoneCall, AlertTriangle, AlertCircle, ShieldAlert, Star, TrendingDown, Ban } from 'lucide-react';
import api from '../api/client';
import type { CcInsightsDashboardSummary, CcInsightsDrillDownFilters } from '../api/types';

const FINDING_LABELS: Record<string, string> = {
  melhoria: 'Melhorias',
  falha: 'Falhas de processo',
  treinamento: 'Treinamento',
  tendencia: 'Tendências',
};

/**
 * InsightsDashboardTab (Call Center) — métricas agregadas do pipeline de IA sobre as
 * gravações de fila (Fase 8). Sem recharts (não é dependência desta SPA) — usa os tiles
 * `.kpi-card` já existentes no design system do Call Center em vez dos gráficos de barra
 * da SPA de Insights (Verint). Clicar num KPI filtra a aba Chamadas pelo valor clicado.
 */
export function InsightsDashboardTab({ onDrillDown }: { onDrillDown: (filters: CcInsightsDrillDownFilters) => void }) {
  const [summary, setSummary] = useState<CcInsightsDashboardSummary | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.get<CcInsightsDashboardSummary>('/callcenter/insights/dashboard')
      .then(r => setSummary(r.data))
      .catch(err => {
        console.error('Erro ao carregar dashboard de Insights do Call Center:', err);
        setSummary(null);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <div className="loading-state"><div className="spinner" />Carregando dashboard…</div>;
  }

  if (!summary || summary.totalChamadas === 0) {
    return (
      <>
        <div className="page-header">
          <div><h1>Insights — Dashboard</h1><p>Indicadores agregados das chamadas de fila analisadas por IA</p></div>
        </div>
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
          Sem chamadas analisadas ainda
        </div>
      </>
    );
  }

  const urgentes = summary.porCriticidade.urgente ?? 0;
  const altas = summary.porCriticidade.alta ?? 0;
  const falhas = summary.achadosPorTipo.falha ?? 0;
  const categoriasOrdenadas = Object.entries(summary.porCategoria).sort(([, a], [, b]) => b - a).slice(0, 8);
  const achadosOrdenados = Object.entries(summary.achadosPorTipo).sort(([, a], [, b]) => b - a);

  return (
    <>
      <div className="page-header">
        <div><h1>Insights — Dashboard</h1><p>Indicadores agregados das chamadas de fila analisadas por IA</p></div>
      </div>

      <div className="kpi-grid">
        <div className="kpi-card" style={{ cursor: 'pointer' }} onClick={() => onDrillDown({})} title="Ver todas as chamadas">
          <div className="kpi-card-top"><span className="kpi-icon info"><PhoneCall size={18} /></span></div>
          <div className="kpi-value">{summary.totalChamadas}</div>
          <div className="kpi-label">Chamadas analisadas</div>
        </div>
        <div className="kpi-card" style={{ cursor: 'pointer' }} onClick={() => onDrillDown({ criticidade: 'urgente' })} title="Ver chamadas com criticidade urgente">
          <div className="kpi-card-top"><span className="kpi-icon danger"><ShieldAlert size={18} /></span></div>
          <div className="kpi-value">{urgentes}</div>
          <div className="kpi-label">Criticidade urgente</div>
        </div>
        <div className="kpi-card" style={{ cursor: 'pointer' }} onClick={() => onDrillDown({ criticidade: 'alta' })} title="Ver chamadas com criticidade alta">
          <div className="kpi-card-top"><span className="kpi-icon warning"><AlertTriangle size={18} /></span></div>
          <div className="kpi-value">{altas}</div>
          <div className="kpi-label">Criticidade alta</div>
        </div>
        <div className="kpi-card" style={{ cursor: 'pointer' }} onClick={() => onDrillDown({ findingType: 'falha' })} title="Ver chamadas com falha de processo">
          <div className="kpi-card-top"><span className="kpi-icon danger"><AlertCircle size={18} /></span></div>
          <div className="kpi-value">{falhas}</div>
          <div className="kpi-label">Falhas de processo</div>
        </div>
        <div className="kpi-card" title="Média das notas por agente (Fichas de avaliação)">
          <div className="kpi-card-top"><span className="kpi-icon success"><Star size={18} /></span></div>
          <div className="kpi-value">{summary.mediaNotaGeral.toFixed(1)}</div>
          <div className="kpi-label">Nota média geral</div>
        </div>
        <div className="kpi-card" title="Agentes com nota média abaixo da média geral">
          <div className="kpi-card-top"><span className="kpi-icon warning"><TrendingDown size={18} /></span></div>
          <div className="kpi-value">{summary.agentesAbaixoMedia}</div>
          <div className="kpi-label">Agentes abaixo da média</div>
        </div>
        <div className="kpi-card" style={{ cursor: 'pointer' }} onClick={() => onDrillDown({ isFailed: true })} title="Ver chamadas reprovadas por item crítico (auto-fail)">
          <div className="kpi-card-top"><span className="kpi-icon danger"><Ban size={18} /></span></div>
          <div className="kpi-value">{summary.autoFailsNoPeriodo}</div>
          <div className="kpi-label">Auto-fails no período</div>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        {achadosOrdenados.length > 0 && (
          <div className="card">
            <div className="card-header"><span className="card-title">💡 Achados por tipo</span></div>
            <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {achadosOrdenados.map(([tipo, count]) => (
                <div
                  key={tipo}
                  style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '8px 12px', borderRadius: 8, background: 'var(--bg-input)',
                    border: '1px solid var(--border-glass)', cursor: 'pointer',
                  }}
                  onClick={() => onDrillDown({ findingType: tipo })}
                >
                  <span style={{ fontSize: '.85rem' }}>{FINDING_LABELS[tipo] ?? tipo}</span>
                  <span className="badge badge-info">{count}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {categoriasOrdenadas.length > 0 && (
          <div className="card">
            <div className="card-header"><span className="card-title">📊 Top categorias/assuntos</span></div>
            <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {categoriasOrdenadas.map(([categoria, count]) => (
                <div
                  key={categoria}
                  style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '8px 12px', borderRadius: 8, background: 'var(--bg-input)',
                    border: '1px solid var(--border-glass)', cursor: 'pointer',
                  }}
                  onClick={() => onDrillDown({ categoria })}
                >
                  <span style={{ fontSize: '.85rem' }}>{categoria}</span>
                  <span className="badge badge-success">{count}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </>
  );
}
