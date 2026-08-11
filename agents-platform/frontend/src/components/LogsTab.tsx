import { useEffect, useState } from 'react';
import { Download, Terminal } from 'lucide-react';
import api from '../api/client';
import { LogConsole } from './LogConsole';
import type { Agent, Execution, LogEntry, PaginatedResponse } from '../api/types';

export function LogsTab() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [execs, setExecs] = useState<Execution[]>([]);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [selAgent, setSelAgent] = useState('');
  const [selExec, setSelExec] = useState('');
  const [loadingLogs, setLoadingLogs] = useState(false);

  useEffect(() => {
    api.get<PaginatedResponse<Agent> | Agent[]>('/api/agents/?limit=200')
      .then(({ data }) => setAgents(Array.isArray(data) ? data : data.items))
      .catch(() => setAgents([]));
  }, []);

  useEffect(() => {
    if (!selAgent) return;
    setExecs([]); setLogs([]); setSelExec('');
    api.get<PaginatedResponse<Execution> | Execution[]>(`/api/executions/?agent_id=${selAgent}&limit=30`)
      .then(({ data }) => setExecs(Array.isArray(data) ? data : data.items))
      .catch(() => setExecs([]));
  }, [selAgent]);

  useEffect(() => {
    if (!selExec) return;
    setLogs([]);
    setLoadingLogs(true);
    api.get<LogEntry[]>(`/api/executions/${selExec}/logs?limit=500`)
      .then(({ data }) => setLogs(Array.isArray(data) ? data : []))
      .catch(() => setLogs([]))
      .finally(() => setLoadingLogs(false));
  }, [selExec]);

  return (
    <>
      <div className="page-header"><h1>Logs de Execução</h1><p>Console de logs por agente e execução</p></div>
      <div className="page-body">
        <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
          <div className="form-group" style={{ flex: 1, minWidth: 200 }}>
            <label className="form-label" htmlFor="logs-agent">Agente</label>
            <select id="logs-agent" className="form-select" value={selAgent} onChange={e => { setSelAgent(e.target.value); setExecs([]); setLogs([]); }}>
              <option value="">Selecione um agente...</option>
              {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
          <div className="form-group" style={{ flex: 1, minWidth: 200 }}>
            <label className="form-label" htmlFor="logs-exec">Execução</label>
            <select id="logs-exec" className="form-select" value={selExec} onChange={e => setSelExec(e.target.value)} disabled={!selAgent}>
              <option value="">Selecione uma execução...</option>
              {execs.map(e => (
                <option key={e.id} value={e.id}>
                  {new Date(e.started_at).toLocaleString('pt-BR')} — {e.status} — {e.passed_checks ?? 0}/{e.total_checks ?? 0} OK
                </option>
              ))}
            </select>
          </div>
          {selExec && (
            <div style={{ display: 'flex', alignItems: 'flex-end', paddingBottom: 4 }}>
              <a href={`/agents/api/reports/execution/${selExec}/html`} target="_blank" rel="noopener noreferrer" className="btn btn-primary">
                <Download size={13} /> Exportar relatório
              </a>
            </div>
          )}
        </div>

        {!selAgent && (
          <div className="card"><div className="card-body table-empty">
            <Terminal size={40} style={{ opacity: 0.4, marginBottom: 8 }} />
            <p>Selecione um agente para ver os logs.</p>
          </div></div>
        )}

        {selExec && (
          <div className="card">
            <div className="card-header">
              <span className="card-title">Log da execução</span>
              <span className="text-muted" style={{ fontSize: '0.8rem' }}>{logs.length} linhas</span>
            </div>
            <div className="card-body">
              <LogConsole logs={logs} loading={loadingLogs} height={460} />
            </div>
          </div>
        )}
      </div>
    </>
  );
}
