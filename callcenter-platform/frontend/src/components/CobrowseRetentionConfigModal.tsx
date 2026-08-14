import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { CobrowseRetentionConfig, CobrowseRetentionRunResult } from '../api/types';

/**
 * CobrowseRetentionConfigModal — configuração de retenção do co-browsing (Fase 17d), mesmo
 * padrão de {@code RetencaoAlertaTab.tsx} (gravação de voz): prazo em dias, último expurgo,
 * disparo manual — sem alerta de disco (fora de escopo desta sub-fase).
 */
export function CobrowseRetentionConfigModal({ canWrite, onClose }: { canWrite: boolean; onClose: () => void }) {
  const [retention, setRetention] = useState<CobrowseRetentionConfig | null>(null);
  const [retentionDays, setRetentionDays] = useState(1826);
  const [msg, setMsg] = useState('');
  const [purging, setPurging] = useState(false);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 5000); };

  const load = () => {
    api.get<CobrowseRetentionConfig>('/callcenter/cobrowsing/retention-config')
      .then(({ data }) => { setRetention(data); setRetentionDays(data.retentionDays); })
      .catch(() => setRetention(null));
  };
  useEffect(load, []);

  const saveRetention = () => {
    api.put<CobrowseRetentionConfig>('/callcenter/cobrowsing/retention-config', { retentionDays })
      .then(({ data }) => { setRetention(data); flash('Prazo de retenção salvo.'); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar retenção.')));
  };

  const runPurgeNow = () => {
    setPurging(true);
    api.post<CobrowseRetentionRunResult>('/callcenter/cobrowsing/retention-config/run')
      .then(({ data }) => { flash(`Expurgo concluído: ${data.purgedCount} sessão(ões) expurgada(s).`); load(); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao rodar expurgo.')))
      .finally(() => setPurging(false));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Retenção de co-browsing</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          {msg && <div className="flash-message">{msg}</div>}

          <div className="form-grid">
            <div className="form-group">
              <label className="form-label">Prazo de retenção (dias)</label>
              <input type="number" className="form-input" value={retentionDays} disabled={!canWrite}
                min={1} max={36500}
                onChange={e => setRetentionDays(Number(e.target.value))} />
            </div>
          </div>
          {retention && (
            <p style={{ color: 'var(--text-muted)', fontSize: '.85rem' }}>
              Último expurgo: {retention.lastPurgeAt ? new Date(retention.lastPurgeAt).toLocaleString('pt-BR') : 'nunca rodou'}
              {retention.lastPurgeDeletedCount != null && ` — ${retention.lastPurgeDeletedCount} sessão(ões) expurgada(s)`}
            </p>
          )}
          {canWrite && (
            <div className="modal-footer" style={{ padding: '8px 0' }}>
              <button className="btn btn-ghost" onClick={runPurgeNow} disabled={purging}>
                {purging ? 'Rodando...' : 'Rodar expurgo agora'}
              </button>
              <button className="btn btn-primary" onClick={saveRetention}>Salvar retenção</button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
