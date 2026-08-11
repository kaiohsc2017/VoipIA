import { useEffect, useState } from 'react';
import { Download, X } from 'lucide-react';
import api from '../api/client';
import { LogConsole } from './LogConsole';
import type { Execution, LogEntry, PaginatedResponse } from '../api/types';

/** Mirror de LogModal (index.html:526-610) — modal de logs de um agente específico. */
export function AgentLogModal({ agentId, onClose }: { agentId: string; onClose: () => void }) {
  const [execs, setExecs] = useState<Execution[]>([]);
  const [selExec, setSelExec] = useState('');
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.get<PaginatedResponse<Execution> | Execution[]>(`/api/executions/?agent_id=${agentId}&limit=20`)
      .then(({ data }) => {
        const list = Array.isArray(data) ? data : data.items;
        if (list.length === 0) { setExecs([]); setLoading(false); return; }
        setExecs(list);
        setSelExec(list[0].id);
        return api.get<LogEntry[]>(`/api/executions/${list[0].id}/logs?limit=500`)
          .then(({ data: logData }) => setLogs(Array.isArray(logData) ? logData : []))
          .finally(() => setLoading(false));
      })
      .catch(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId]);

  const selectExec = (execId: string) => {
    setSelExec(execId);
    setLoading(true);
    api.get<LogEntry[]>(`/api/executions/${execId}/logs?limit=500`)
      .then(({ data }) => setLogs(Array.isArray(data) ? data : []))
      .catch(() => setLogs([]))
      .finally(() => setLoading(false));
  };

  return (
    <div className="modal-overlay">
      <div className="modal">
        <div className="modal-header">
          <h2>Logs de execução</h2>
          <button className="btn-close" onClick={onClose}><X size={18} /></button>
        </div>
        <div className="modal-body">
          <div className="form-group">
            <label className="form-label">Execução</label>
            <select className="form-select" value={selExec} onChange={e => selectExec(e.target.value)} disabled={execs.length === 0}>
              {execs.length === 0 ? (
                <option>Nenhuma execução encontrada</option>
              ) : execs.map(e => (
                <option key={e.id} value={e.id}>
                  {new Date(e.started_at).toLocaleString('pt-BR')} — {e.status}{e.total_checks ? ` — ${e.passed_checks ?? 0}/${e.total_checks} OK` : ''}
                </option>
              ))}
            </select>
          </div>
          {selExec && (
            <div style={{ marginBottom: 8 }}>
              <a href={`/agents/api/reports/execution/${selExec}/html`} target="_blank" rel="noopener noreferrer" className="btn btn-ghost btn-sm">
                <Download size={13} /> Exportar HTML
              </a>
            </div>
          )}
          <LogConsole logs={logs} loading={loading} />
        </div>
      </div>
    </div>
  );
}
