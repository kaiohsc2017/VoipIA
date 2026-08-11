import { useEffect, useState } from 'react';
import api from '../api/client';
import type { AlertEntry } from '../api/types';

const LEVEL_TONE: Record<AlertEntry['level'], string> = {
  info: 'badge-info',
  warning: 'badge-warning',
  error: 'badge-danger',
  critical: 'badge-danger',
};

export function AlertsTab() {
  const [alerts, setAlerts] = useState<AlertEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get<AlertEntry[]>('/api/executions/alerts?limit=100')
      .then(({ data }) => setAlerts(Array.isArray(data) ? data : []))
      .catch(() => setAlerts([]))
      .finally(() => setLoading(false));
  }, []);

  return (
    <>
      <div className="page-header"><h1>Alertas</h1><p>Histórico de alertas enviados pelos agentes</p></div>
      <div className="page-body">
        <div className="card">
          <div className="card-header">
            <span className="card-title">Alertas recentes</span>
            <span className="text-muted" style={{ fontSize: '0.8rem' }}>{alerts.length} alerta{alerts.length !== 1 ? 's' : ''}</span>
          </div>
          <div className="table-wrapper">
            <table>
              <thead>
                <tr><th>Agente</th><th>Nível</th><th>Canal</th><th>Mensagem</th><th>Enviado em</th></tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={5} className="table-empty">Carregando...</td></tr>
                ) : alerts.length === 0 ? (
                  <tr><td colSpan={5} className="table-empty">Nenhum alerta registrado</td></tr>
                ) : alerts.map(a => (
                  <tr key={a.id}>
                    <td>{a.agent_name}</td>
                    <td><span className={`badge ${LEVEL_TONE[a.level] ?? 'badge-gray'}`}>{a.level}</span></td>
                    <td><span className="badge badge-gray">{a.channel}</span></td>
                    <td style={{ maxWidth: 340, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.message}</td>
                    <td className="td-muted">{new Date(a.sent_at).toLocaleString('pt-BR')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}
