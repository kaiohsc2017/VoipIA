import { useState } from 'react';
import { InsightsTab } from './InsightsTab';
import { InsightsDashboardTab } from './InsightsDashboardTab';

/**
 * ModuloInsights — transcrição e análise de IA de gravações do call center
 * corporativo Verint (/opt/audio). Módulo apartado do domínio Asterisk
 * (call_records/uras) — mesmo padrão de abas do ModuloURA.
 */
export default function ModuloInsights() {
  const [tab, setTab] = useState<'calls' | 'dashboard'>('calls');

  return (
    <>
      <div className="page-header">
        <h1>💡 Insights</h1>
        <p>Transcrição e análise de IA das gravações do call center</p>
      </div>
      <div className="page-body">
        <div className="flex gap-1 mb-2" style={{ marginBottom: 20, display: 'flex', gap: 6 }}>
          <button className={`btn ${tab === 'calls' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('calls')}>
            📋 Chamadas
          </button>
          <button className={`btn ${tab === 'dashboard' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setTab('dashboard')}>
            📈 Dashboard de Tendências
          </button>
        </div>

        {tab === 'calls' && <InsightsTab />}
        {tab === 'dashboard' && <InsightsDashboardTab />}
      </div>
    </>
  );
}
