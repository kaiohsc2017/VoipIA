import { useEffect, useState } from 'react';
import api from '../api/client';
import type { CostAlertConfigView } from '../api/types';
import { useAuthSession } from '../hooks/useAuthSession';
import type { FinanceiroScope } from './Financeiro';

function formatUsd(value: number) {
  return `US$ ${value.toFixed(2)}`;
}

/** Aba "Alerta de Gasto" do módulo Financeiro — configura o limite mensal (USD) de uma
 * frente (URA/Insights/Análise Sob Demanda); ao ser atingido, o backend
 * (CostAlertScheduler, diário) envia um alerta via Telegram, uma vez por mês. */
export function CostAlertPanel({ scope }: { scope: FinanceiroScope }) {
  const { hasWrite } = useAuthSession();
  const canEdit = hasWrite(`financeiro.${scope}`);

  const [config, setConfig] = useState<CostAlertConfigView | null>(null);
  const [loading, setLoading] = useState(true);
  const [enabled, setEnabled] = useState(false);
  const [threshold, setThreshold] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setLoading(true);
    api.get<CostAlertConfigView>(`/financeiro/cost-alerts/${scope}`)
      .then(r => {
        setConfig(r.data);
        setEnabled(r.data.enabled);
        setThreshold(String(r.data.thresholdUsd));
      })
      .catch(err => console.error('Erro ao carregar configuração de alerta:', err))
      .finally(() => setLoading(false));
  }, [scope]);

  const save = () => {
    const thresholdUsd = Number(threshold);
    if (!Number.isFinite(thresholdUsd) || thresholdUsd < 0) {
      alert('Informe um limite válido em USD.');
      return;
    }
    setSaving(true);
    api.put<CostAlertConfigView>(`/financeiro/cost-alerts/${scope}`, { thresholdUsd, enabled })
      .then(r => setConfig(r.data))
      .catch(err => {
        console.error('Erro ao salvar configuração de alerta:', err);
        alert('Erro ao salvar a configuração — tente novamente.');
      })
      .finally(() => setSaving(false));
  };

  if (loading) {
    return <div className="loading-state"><div className="spinner" />Carregando configuração…</div>;
  }

  return (
    <div className="stat-card" style={{ padding: 20, maxWidth: 480 }}>
      <h3 style={{ marginBottom: 4, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
        🔔 Alerta de gasto (Telegram)
      </h3>
      <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 16 }}>
        Quando o gasto de IA desta frente no mês corrente ultrapassar o limite abaixo, um
        alerta é enviado no Telegram — no máximo uma vez por mês. Verificado diariamente.
      </p>

      <div style={{ marginBottom: 14 }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.85rem', cursor: canEdit ? 'pointer' : 'default' }}>
          <input
            type="checkbox"
            checked={enabled}
            disabled={!canEdit}
            onChange={e => setEnabled(e.target.checked)}
          />
          Habilitar alerta
        </label>
      </div>

      <div style={{ marginBottom: 16 }}>
        <label className="form-label">Limite mensal (USD)</label>
        <input
          type="number" min="0" step="0.01"
          className="form-input" style={{ maxWidth: 200 }}
          value={threshold}
          disabled={!canEdit}
          onChange={e => setThreshold(e.target.value)}
        />
      </div>

      {config && (
        <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: 16 }}>
          Gasto no mês corrente: <strong style={{ color: 'var(--text-secondary)' }}>{formatUsd(config.currentMonthSpendUsd)}</strong>
          {config.lastNotifiedMonth && (
            <> — último alerta enviado em {config.lastNotifiedMonth}</>
          )}
        </div>
      )}

      {canEdit && (
        <button className="btn btn-primary btn-sm" onClick={save} disabled={saving}>
          {saving ? 'Salvando…' : 'Salvar'}
        </button>
      )}
    </div>
  );
}
