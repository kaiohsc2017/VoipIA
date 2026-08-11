import { useEffect, useState } from 'react';
import api from '../api/client';
import type { Ura } from '../api/types';
import { CostsTab } from './CostsTab';
import { CostsDashboardTab } from './CostsDashboardTab';
import { InsightsCostsTab } from './InsightsCostsTab';
import { InsightsCostsDashboardTab } from './InsightsCostsDashboardTab';
import { CostAlertPanel } from './CostAlertPanel';

export type FinanceiroScope = 'ura' | 'insights' | 'envios';

const SCOPE_META: Record<FinanceiroScope, { title: string; icon: string; basePath: string; summaryPath: string }> = {
  ura:      { title: 'URA',                 icon: '🎫', basePath: '/calls/costs',           summaryPath: '/calls/costs/summary' },
  insights: { title: 'Insights',            icon: '💡', basePath: '/insights/costs',         summaryPath: '/insights/costs/summary' },
  envios:   { title: 'Análise Sob Demanda', icon: '📤', basePath: '/insights/uploads/costs', summaryPath: '/insights/uploads/costs/summary' },
};

/** Módulo Financeiro — centraliza as telas de Custo de IA (lista + dashboard) das 3
 * frentes de uso (URA, Insights, Análise Sob Demanda), antes espalhadas como abas dentro
 * do Módulo URA e da SPA Insights. Reusa os mesmos componentes de tela já existentes —
 * CostsTab/CostsDashboardTab para URA (consome /calls/costs direto); InsightsCostsTab/
 * InsightsCostsDashboardTab (mirror, portado da SPA Insights) para Insights/Análise Sob
 * Demanda, parametrizados por basePath. */
export default function Financeiro({ scope }: { scope: FinanceiroScope }) {
  const [tab, setTab] = useState<'costs' | 'costsDashboard' | 'alert'>('costs');
  const [uras, setUras] = useState<Ura[]>([]);
  const [costsRange, setCostsRange] = useState<{ dateFrom: string; dateTo: string } | null>(null);
  const meta = SCOPE_META[scope];

  useEffect(() => {
    if (scope !== 'ura') return;
    api.get<Ura[]>('/uras').then(r => setUras(r.data))
      .catch(err => console.error('Erro ao carregar URAs:', err));
  }, [scope]);

  /** Drill-down vindo do Dashboard de Custos: troca para a aba "Custos IA" já com o
   * range do mês clicado (mesmo padrão de handleCostsDrillDown do antigo ModuloURA).
   * Tipado pela forma mínima realmente usada (não o union de RankingDrillDownFilters/
   * FinanceiroDrillDownFilters) — contravariante o suficiente para servir aos dois
   * componentes de dashboard, sem herdar campos não usados de nenhum dos dois DTOs. */
  const handleCostsDrillDown = (filters: { dateFrom?: string; dateTo?: string }) => {
    setCostsRange({ dateFrom: filters.dateFrom ?? '', dateTo: filters.dateTo ?? '' });
    setTab('costs');
  };

  return (
    <>
      <div className="page-header">
        <h1>{meta.icon} Financeiro — {meta.title}</h1>
        <p>Custo de consumo de IA (tokens) e evolução de gastos</p>
      </div>
      <div className="page-body">
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20, display: 'flex', gap: 6 }}>
          <button className={`btn ${tab === 'costs' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('costs')}>
            💰 Custos IA
          </button>
          <button className={`btn ${tab === 'costsDashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('costsDashboard')}>
            📈 Dashboard de Custos
          </button>
          <button className={`btn ${tab === 'alert' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('alert')}>
            🔔 Alerta de Gasto
          </button>
        </div>

        {tab === 'costs' && scope === 'ura' && (
          <CostsTab
            uras={uras}
            initialDateFrom={costsRange?.dateFrom}
            initialDateTo={costsRange?.dateTo}
            onInitialFiltersConsumed={() => setCostsRange(null)}
          />
        )}
        {tab === 'costs' && scope !== 'ura' && (
          <InsightsCostsTab
            basePath={meta.basePath}
            onDrillDown={() => {}}
            initialDateFrom={costsRange?.dateFrom}
            initialDateTo={costsRange?.dateTo}
            onInitialFiltersConsumed={() => setCostsRange(null)}
          />
        )}

        {tab === 'costsDashboard' && scope === 'ura' && (
          <CostsDashboardTab uras={uras} onDrillDown={handleCostsDrillDown} />
        )}
        {tab === 'costsDashboard' && scope !== 'ura' && (
          <InsightsCostsDashboardTab basePath={meta.summaryPath} onDrillDown={handleCostsDrillDown} />
        )}

        {tab === 'alert' && <CostAlertPanel scope={scope} />}
      </div>
    </>
  );
}
