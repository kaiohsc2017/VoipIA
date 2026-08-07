import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { DiskAlertConfig, RetentionConfig, RetentionRunResult } from '../api/types';

/**
 * RetencaoAlertaTab — configuração de retenção (expurgo automático de gravações antigas) e
 * alerta de disco do volume de gravações. Extraído de GravacoesTab.tsx (Fase 3) para manter
 * cada arquivo dentro do padrão de tamanho do projeto (~200-400 linhas) — aberto em modal a
 * partir do botão "Configurações" da lista de gravações.
 */
export function RetencaoAlertaTab({ canWrite, onClose }: { canWrite: boolean; onClose: () => void }) {
  const [retention, setRetention] = useState<RetentionConfig | null>(null);
  const [diskAlert, setDiskAlert] = useState<DiskAlertConfig | null>(null);
  const [retentionDays, setRetentionDays] = useState(1800);
  const [thresholdPercent, setThresholdPercent] = useState(85);
  const [diskAlertEnabled, setDiskAlertEnabled] = useState(true);
  const [msg, setMsg] = useState('');
  const [purging, setPurging] = useState(false);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 5000); };

  const load = () => {
    api.get<RetentionConfig>('/callcenter/recordings/retention-config')
      .then(({ data }) => { setRetention(data); setRetentionDays(data.retentionDays); })
      .catch(() => setRetention(null));
    api.get<DiskAlertConfig>('/callcenter/recordings/disk-alert-config')
      .then(({ data }) => { setDiskAlert(data); setThresholdPercent(data.thresholdPercent); setDiskAlertEnabled(data.enabled); })
      .catch(() => setDiskAlert(null));
  };
  useEffect(load, []);

  const saveRetention = () => {
    api.put<RetentionConfig>('/callcenter/recordings/retention-config', { retentionDays })
      .then(({ data }) => { setRetention(data); flash('Prazo de retenção salvo.'); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar retenção.')));
  };

  const saveDiskAlert = () => {
    api.put<DiskAlertConfig>('/callcenter/recordings/disk-alert-config', { thresholdPercent, enabled: diskAlertEnabled })
      .then(({ data }) => { setDiskAlert(data); flash('Alerta de disco salvo.'); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar alerta de disco.')));
  };

  const runPurgeNow = () => {
    setPurging(true);
    api.post<RetentionRunResult>('/callcenter/recordings/retention-config/run')
      .then(({ data }) => { flash(`Expurgo concluído: ${data.deletedCount} gravação(ões) removida(s).`); load(); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao rodar expurgo.')))
      .finally(() => setPurging(false));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal-lg" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Retenção e alerta de disco</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          {msg && <div className="flash-message">{msg}</div>}

          <h3>Retenção de gravações</h3>
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
              {retention.lastPurgeDeletedCount != null && ` — ${retention.lastPurgeDeletedCount} gravação(ões) removida(s)`}
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

          <hr style={{ margin: '20px 0' }} />

          <h3>Alerta de disco</h3>
          <div className="form-grid">
            <div className="form-group">
              <label className="form-label">Limite de uso (%)</label>
              <input type="number" className="form-input" value={thresholdPercent} disabled={!canWrite}
                min={1} max={100}
                onChange={e => setThresholdPercent(Number(e.target.value))} />
            </div>
            <div className="form-group">
              <label className="form-label">
                <input type="checkbox" checked={diskAlertEnabled} disabled={!canWrite}
                  onChange={e => setDiskAlertEnabled(e.target.checked)} />
                {' '}Alerta habilitado
              </label>
            </div>
          </div>
          {diskAlert?.currentUsagePercent != null && (
            <p style={{ color: 'var(--text-muted)', fontSize: '.85rem' }}>
              Uso atual do volume: {diskAlert.currentUsagePercent.toFixed(1)}%
              {diskAlert.lastNotifiedDate && ` — último alerta enviado em ${diskAlert.lastNotifiedDate}`}
            </p>
          )}
          {canWrite && (
            <div className="modal-footer" style={{ padding: '8px 0' }}>
              <button className="btn btn-primary" onClick={saveDiskAlert}>Salvar alerta de disco</button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
