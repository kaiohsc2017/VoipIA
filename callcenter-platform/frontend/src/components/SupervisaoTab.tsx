import { useEffect, useState } from 'react';
import { Headphones, Mic, LogIn, Pause, Play, Maximize2, X } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type { SupervisionSnapshot, AgentSupervisionView } from '../api/types';

const STATE_LABEL: Record<string, string> = {
  DISPONIVEL: 'Disponível',
  EM_ATENDIMENTO: 'Em atendimento',
  ACW: 'ACW',
  PAUSA: 'Pausa',
  OFFLINE: 'Offline',
};

const POLL_INTERVAL_MS = 4000;

function formatSeconds(seconds?: number): string {
  if (seconds == null) return '—';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${s.toString().padStart(2, '0')}`;
}

/**
 * SupervisaoTab — painel em tempo (quase) real de filas/agentes e ações do supervisor
 * (Fase 6). Atualização por polling (4s) — a mesma estatística já é publicada via WebSocket
 * STOMP pelo backend para consumidores futuros, mas esta SPA ainda não tem cliente STOMP
 * próprio (padrão já usado em DesktopAgenteTab).
 */
export function SupervisaoTab({ canWrite }: { canWrite: boolean }) {
  const [snapshot, setSnapshot] = useState<SupervisionSnapshot>({ queues: [], agents: [] });
  const [error, setError] = useState('');
  const [wallboard, setWallboard] = useState(false);
  const [busyAgentId, setBusyAgentId] = useState<number | null>(null);

  const load = () => {
    api.get<SupervisionSnapshot>('/callcenter/supervision/snapshot')
      .then(({ data }) => setSnapshot(data))
      .catch(() => {});
  };

  useEffect(() => {
    load();
    const id = setInterval(load, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, []);

  const runAction = (agentId: number, action: string) => {
    setError('');
    setBusyAgentId(agentId);
    api.post(`/callcenter/supervision/agents/${agentId}/${action}`)
      .catch(err => setError(getErrorMessage(err, 'Erro ao executar a ação de supervisão.')))
      .finally(() => setBusyAgentId(null));
  };

  const renderAgentRow = (agent: AgentSupervisionView) => {
    const isInCall = agent.state === 'EM_ATENDIMENTO';
    const isPausa = agent.state === 'PAUSA';
    return (
      <tr key={agent.agentId}>
        <td>{agent.agentName} <span style={{ color: 'var(--text-muted)' }}>({agent.extension ?? '—'})</span></td>
        <td><span className="badge badge-gray">{STATE_LABEL[agent.state ?? ''] ?? '—'}</span> {agent.pauseReasonLabel && `— ${agent.pauseReasonLabel}`}</td>
        <td>{formatSeconds(agent.secondsInState)}</td>
        <td>{agent.answeredToday}</td>
        {canWrite && (
          <td>
            <div className="flex items-center" style={{ gap: 4 }}>
              <button className="btn btn-ghost btn-sm" disabled={!isInCall || busyAgentId === agent.agentId}
                title="Escutar" onClick={() => runAction(agent.agentId, 'listen')}>
                <Headphones size={14} />
              </button>
              <button className="btn btn-ghost btn-sm" disabled={!isInCall || busyAgentId === agent.agentId}
                title="Sussurrar" onClick={() => runAction(agent.agentId, 'whisper')}>
                <Mic size={14} />
              </button>
              <button className="btn btn-ghost btn-sm" disabled={!isInCall || busyAgentId === agent.agentId}
                title="Interceptar" onClick={() => runAction(agent.agentId, 'barge')}>
                <LogIn size={14} />
              </button>
              {isPausa ? (
                <button className="btn btn-ghost btn-sm" disabled={busyAgentId === agent.agentId}
                  title="Forçar despausa" onClick={() => runAction(agent.agentId, 'force-unpause')}>
                  <Play size={14} />
                </button>
              ) : (
                <button className="btn btn-ghost btn-sm" disabled={isInCall || busyAgentId === agent.agentId}
                  title="Forçar pausa" onClick={() => runAction(agent.agentId, 'force-pause')}>
                  <Pause size={14} />
                </button>
              )}
            </div>
          </td>
        )}
      </tr>
    );
  };

  const content = (
    <div className={wallboard ? 'page-body' : undefined}>
      {error && <div className="alert alert-error" style={{ marginBottom: 16 }}>{error}</div>}

      <div className="table-wrapper" style={{ marginBottom: 24 }}>
        <table>
          <thead>
            <tr><th>Fila</th><th>Em espera</th><th>Maior espera</th><th>Atendidas hoje</th><th>Abandonadas hoje</th><th>Nível de serviço</th></tr>
          </thead>
          <tbody>
            {snapshot.queues.map(q => (
              <tr key={q.queueId}>
                <td>{q.displayName}</td>
                <td>{q.waitingCount}</td>
                <td>{formatSeconds(q.longestWaitSeconds)}</td>
                <td>{q.answeredToday}</td>
                <td>{q.abandonedToday}</td>
                <td>{q.serviceLevelPercent == null ? '—' : `${q.serviceLevelPercent.toFixed(0)}%`}</td>
              </tr>
            ))}
            {snapshot.queues.length === 0 && <tr><td colSpan={6} className="table-empty">Nenhuma fila cadastrada.</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="table-wrapper">
        <table>
          <thead>
            <tr><th>Agente</th><th>Estado</th><th>Tempo no estado</th><th>Atendidas hoje</th>{canWrite && <th>Ações</th>}</tr>
          </thead>
          <tbody>
            {snapshot.agents.map(renderAgentRow)}
            {snapshot.agents.length === 0 && <tr><td colSpan={canWrite ? 5 : 4} className="table-empty">Nenhum agente cadastrado.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );

  if (wallboard) {
    return (
      <div style={{ position: 'fixed', inset: 0, background: 'var(--bg-base)', zIndex: 1000, overflow: 'auto', padding: 24 }}>
        <div className="flex items-center justify-between" style={{ marginBottom: 16 }}>
          <h1>Supervisão — Modo TV</h1>
          <button className="btn btn-ghost" onClick={() => setWallboard(false)}><X size={16} /> Sair do modo TV</button>
        </div>
        {content}
      </div>
    );
  }

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Supervisão</h1><p>Filas e agentes em tempo (quase) real</p></div>
          <button className="btn btn-ghost" onClick={() => setWallboard(true)}><Maximize2 size={14} /> Modo TV</button>
        </div>
      </div>
      {content}
    </>
  );
}
