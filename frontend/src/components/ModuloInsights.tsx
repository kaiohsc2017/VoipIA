import { useState } from 'react';
import { InsightsTab } from './InsightsTab';
import { InsightsDashboardTab, type InsightsDrillDownFilters } from './InsightsDashboardTab';
import { InsightsCostsTab } from './InsightsCostsTab';
import { InsightsCostsDashboardTab } from './InsightsCostsDashboardTab';
import { InsightsProcessingTab } from './InsightsProcessingTab';

/**
 * ModuloInsights — transcrição e análise de IA de gravações do call center
 * corporativo Verint (/opt/audio). Módulo apartado do domínio Asterisk
 * (call_records/uras) — mesmo padrão de abas do ModuloURA.
 */
export default function ModuloInsights() {
  const [tab, setTab] = useState<'calls' | 'dashboard' | 'costs' | 'costsDashboard' | 'processing'>('calls');
  const [pendingDrillDown, setPendingDrillDown] = useState<{ filters: InsightsDrillDownFilters; nonce: number } | null>(null);

  /**
   * Drill-down vindo do Dashboard de Tendências: troca para a aba Chamadas já
   * com o filtro do indicador clicado — mesmo comportamento do drill-down do
   * Ranking de Atendimentos (URA/ModuloURA.handleDrillDown). O `nonce` garante
   * reprocessar mesmo se o usuário clicar duas vezes seguidas no mesmo indicador.
   */
  const handleDrillDown = (filters: InsightsDrillDownFilters) => {
    setPendingDrillDown(prev => ({ filters, nonce: (prev?.nonce ?? 0) + 1 }));
    setTab('calls');
  };

  /** InsightsTab desmonta/remonta a cada troca de aba (renderização condicional
   * abaixo) — sem isso, o drill-down seria reaplicado a cada volta manual pra
   * aba Chamadas, mesmo sem novo clique no dashboard. */
  const handleDrillDownConsumed = () => setPendingDrillDown(null);

  return (
    <>
      <div className="page-header">
        <h1>💡 Insights</h1>
        <p>Transcrição e análise de IA das gravações do call center</p>
      </div>
      <div className="page-body">
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <button className={`btn ${tab === 'calls' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('calls')}>
            📋 Chamadas
          </button>
          <button className={`btn ${tab === 'dashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('dashboard')}>
            📈 Dashboard de Tendências
          </button>
          <button className={`btn ${tab === 'processing' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('processing')}>
            ⚙️ Processamento
          </button>
          <button className={`btn ${tab === 'costs' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('costs')}>
            💰 Custos IA
          </button>
          <button className={`btn ${tab === 'costsDashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('costsDashboard')}>
            📈 Dashboard de Custos
          </button>
        </div>

        {tab === 'calls' && <InsightsTab pendingDrillDown={pendingDrillDown} onDrillDownConsumed={handleDrillDownConsumed} />}
        {tab === 'dashboard' && <InsightsDashboardTab onDrillDown={handleDrillDown} />}
        {tab === 'processing' && <InsightsProcessingTab />}
        {tab === 'costs' && <InsightsCostsTab />}
        {tab === 'costsDashboard' && <InsightsCostsDashboardTab />}
      </div>
    </>
  );
}
