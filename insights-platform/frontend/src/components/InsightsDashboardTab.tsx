import { useEffect, useState } from 'react';
import api from '../api/client';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import type { InsightsDashboardSummary, InsightsDrillDownFilters } from '../api/types';

const FINDING_LABELS: Record<string, string> = {
  melhoria: 'Melhorias',
  falha: 'Falhas de processo',
  treinamento: 'Treinamento',
  tendencia: 'Tendências',
};

/** Dashboard de tendências — achados agregados (falhas/melhorias/treinamento) e
 * categorias mais frequentes, na mesma linha visual do CostsDashboardTab.
 * Clicar num indicador (KPI tile ou barra) filtra a aba Chamadas pelo valor
 * clicado — mesmo comportamento do Ranking de Atendimentos (URA). */
export function InsightsDashboardTab({ onDrillDown }: { onDrillDown: (filters: InsightsDrillDownFilters) => void }) {
  const [summary, setSummary] = useState<InsightsDashboardSummary | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.get<InsightsDashboardSummary>('/insights/dashboard')
      .then(r => setSummary(r.data))
      .catch(err => {
        console.error('Erro ao carregar dashboard de Insights:', err);
        setSummary(null);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <div className="loading-state"><div className="spinner" />Carregando dashboard…</div>;
  }

  if (!summary || summary.totalChamadas === 0) {
    return (
      <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
        Sem chamadas analisadas ainda
      </div>
    );
  }

  const findingsChartData = Object.entries(summary.achadosPorTipo).map(([tipo, count]) => ({
    tipo: FINDING_LABELS[tipo] ?? tipo,
    tipoRaw: tipo,
    Ocorrências: count,
  }));

  const categoriaChartData = Object.entries(summary.porCategoria)
    .sort(([, a], [, b]) => b - a)
    .slice(0, 8)
    .map(([categoria, count]) => ({ categoria, Chamadas: count }));

  const urgentes = summary.porCriticidade.urgente ?? 0;
  const altas = summary.porCriticidade.alta ?? 0;

  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 10, marginBottom: 20 }}>
        <div className="stat-card" style={{ padding: '12px 16px', cursor: 'pointer' }} onClick={() => onDrillDown({})} title="Ver todas as chamadas">
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Chamadas analisadas</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#007aff' }}>{summary.totalChamadas}</div>
        </div>
        <div className="stat-card" style={{ padding: '12px 16px', cursor: 'pointer' }} onClick={() => onDrillDown({ criticidade: 'urgente' })} title="Ver chamadas com criticidade urgente">
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Criticidade urgente</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#ff3b30' }}>{urgentes}</div>
        </div>
        <div className="stat-card" style={{ padding: '12px 16px', cursor: 'pointer' }} onClick={() => onDrillDown({ criticidade: 'alta' })} title="Ver chamadas com criticidade alta">
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Criticidade alta</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#ff9f0a' }}>{altas}</div>
        </div>
        <div className="stat-card" style={{ padding: '12px 16px', cursor: 'pointer' }} onClick={() => onDrillDown({ findingType: 'falha' })} title="Ver chamadas com falha de processo">
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Falhas de processo</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#34c759' }}>{summary.achadosPorTipo.falha ?? 0}</div>
        </div>
        <div className="stat-card" style={{ padding: '12px 16px' }} title="Média das notas por agente (Fichas de avaliação)">
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Nota média geral</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#5856d6' }}>{summary.mediaNotaGeral.toFixed(1)}</div>
        </div>
        <div className="stat-card" style={{ padding: '12px 16px' }} title="Agentes com nota média abaixo da média geral">
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Agentes abaixo da média</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#ff9500' }}>{summary.agentesAbaixoMedia}</div>
        </div>
        <div className="stat-card" style={{ padding: '12px 16px', cursor: 'pointer' }} onClick={() => onDrillDown({ isFailed: true })} title="Ver chamadas reprovadas por item crítico (auto-fail)">
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Auto-fails no período</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#ff3b30' }}>{summary.autoFailsNoPeriodo}</div>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        {findingsChartData.length > 0 && (
          <div className="stat-card" style={{ padding: 20 }}>
            <h3 style={{ marginBottom: 16, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
              💡 Achados por tipo
            </h3>
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={findingsChartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
                <XAxis dataKey="tipo" tick={{ fill: '#94a3b8', fontSize: 11 }} />
                <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} allowDecimals={false} />
                <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid rgba(148,163,184,0.15)', borderRadius: 8 }} labelStyle={{ color: '#e2e8f0' }} />
                <Bar
                  dataKey="Ocorrências" fill="#007aff" radius={[4, 4, 0, 0]} cursor="pointer"
                  onClick={(entry: unknown) => {
                    const item = entry as { payload?: { tipoRaw?: string } };
                    const tipoRaw = item.payload?.tipoRaw;
                    if (tipoRaw) onDrillDown({ findingType: tipoRaw });
                  }}
                />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}

        {categoriaChartData.length > 0 && (
          <div className="stat-card" style={{ padding: 20 }}>
            <h3 style={{ marginBottom: 16, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
              📊 Top categorias/assuntos
            </h3>
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={categoriaChartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
                <XAxis dataKey="categoria" tick={{ fill: '#94a3b8', fontSize: 11 }} />
                <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} allowDecimals={false} />
                <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid rgba(148,163,184,0.15)', borderRadius: 8 }} labelStyle={{ color: '#e2e8f0' }} />
                <Bar
                  dataKey="Chamadas" fill="#34c759" radius={[4, 4, 0, 0]} cursor="pointer"
                  onClick={(entry: unknown) => {
                    const item = entry as { label?: string; payload?: { categoria?: string } };
                    const categoria = item.payload?.categoria ?? item.label;
                    if (categoria) onDrillDown({ categoria });
                  }}
                />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>
    </div>
  );
}
