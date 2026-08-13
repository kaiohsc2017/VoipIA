import { useEffect, useState } from 'react';
import { Headphones, Mic, LogIn, Pause, Play, Maximize2, X, ChevronDown, ChevronRight } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type { SupervisionSnapshot, AgentSupervisionView, QueueSupervisionView, CcQueue, CcAgent } from '../api/types';

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
export function SupervisaoTab({ canWrite, canRedirect }: { canWrite: boolean; canRedirect: boolean }) {
  const [snapshot, setSnapshot] = useState<SupervisionSnapshot>({ queues: [], agents: [] });
  const [error, setError] = useState('');
  const [wallboard, setWallboard] = useState(false);
  const [busyAgentId, setBusyAgentId] = useState<number | null>(null);
  const [expandedQueueId, setExpandedQueueId] = useState<number | null>(null);
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [agents, setAgents] = useState<CcAgent[]>([]);
  const [busyChannelUniqueId, setBusyChannelUniqueId] = useState<string | null>(null);

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

  useEffect(() => {
    if (!canRedirect) return;
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => {});
    api.get<CcAgent[]>('/callcenter/agentes').then(({ data }) => setAgents(data)).catch(() => {});
  }, [canRedirect]);

  const runAction = (agentId: number, action: string) => {
    setError('');
    setBusyAgentId(agentId);
    api.post(`/callcenter/supervision/agents/${agentId}/${action}`)
      .catch(err => setError(getErrorMessage(err, 'Erro ao executar a ação de supervisão.')))
      .finally(() => setBusyAgentId(null));
  };

  const runRedirect = (queue: QueueSupervisionView, channelUniqueId: string, body: { targetQueueId: number } | { targetAgentId: number }) => {
    setError('');
    setBusyChannelUniqueId(channelUniqueId);
    const path = 'targetQueueId' in body ? 'queue' : 'agent';
    api.post(`/callcenter/supervision/redirect/${path}`, { sourceQueueName: queue.queueName, channelUniqueId, ...body })
      .then(load)
      .catch(err => setError(getErrorMessage(err, 'Erro ao redirecionar a chamada.')))
      .finally(() => setBusyChannelUniqueId(null));
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
                title="Ouvir a chamada (ninguém ouve o supervisor)" onClick={() => runAction(agent.agentId, 'listen')}>
                <Headphones size={14} />
              </button>
              <button className="btn btn-ghost btn-sm" disabled={!isInCall || busyAgentId === agent.agentId}
                title="Falar com o agente (o cliente não ouve)" onClick={() => runAction(agent.agentId, 'whisper')}>
                <Mic size={14} />
              </button>
              <button className="btn btn-ghost btn-sm" disabled={!isInCall || busyAgentId === agent.agentId}
                title="Entrar na conversa (os dois ouvem)" onClick={() => runAction(agent.agentId, 'barge')}>
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

  const renderQueueRows = (q: QueueSupervisionView) => {
    const isExpanded = expandedQueueId === q.queueId;
    const rows = [
      <tr key={q.queueId} style={{ cursor: 'pointer' }} onClick={() => setExpandedQueueId(isExpanded ? null : q.queueId)}>
        <td className="flex items-center" style={{ gap: 4 }}>
          {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          {q.displayName}
        </td>
        <td>{q.waitingCount}</td>
        <td>{formatSeconds(q.longestWaitSeconds)}</td>
        <td>{q.answeredToday}</td>
        <td>{q.abandonedToday}</td>
        <td>{q.serviceLevelPercent == null ? '—' : `${q.serviceLevelPercent.toFixed(0)}%`}</td>
      </tr>,
    ];
    if (isExpanded) {
      rows.push(
        <tr key={`${q.queueId}-detail`}>
          <td colSpan={6} style={{ padding: 0 }}>
            {renderWaitingCallers(q)}
          </td>
        </tr>
      );
    }
    return rows;
  };

  const renderWaitingCallers = (q: QueueSupervisionView) => (
    <div style={{ padding: 12, background: 'var(--bg-subtle)' }}>
      {q.waitingCallers.length === 0 ? (
        <p style={{ color: 'var(--text-muted)', margin: 0 }}>
          Ninguém em espera nesta fila no momento (ou o AMI não respondeu à consulta).
        </p>
      ) : (
        <table>
          <thead>
            <tr><th>Posição</th><th>ANI</th><th>Espera</th>{canRedirect && <th>Ações</th>}</tr>
          </thead>
          <tbody>
            {q.waitingCallers.map(w => (
              <tr key={w.channelUniqueId}>
                <td>{w.position ?? '—'}</td>
                <td>{w.ani ?? '—'}</td>
                <td>{formatSeconds(w.waitSeconds)}</td>
                {canRedirect && (
                  <td>
                    <div className="flex items-center" style={{ gap: 4 }}>
                      <select
                        className="input input-sm"
                        disabled={busyChannelUniqueId === w.channelUniqueId}
                        defaultValue=""
                        onChange={e => {
                          const targetQueueId = Number(e.target.value);
                          if (targetQueueId) runRedirect(q, w.channelUniqueId, { targetQueueId });
                          e.target.value = '';
                        }}
                        title="Mover para outra fila"
                      >
                        <option value="" disabled>Mover para fila...</option>
                        {queues.filter(other => other.id !== q.queueId).map(other => (
                          <option key={other.id} value={other.id}>{other.displayName}</option>
                        ))}
                      </select>
                      <select
                        className="input input-sm"
                        disabled={busyChannelUniqueId === w.channelUniqueId}
                        defaultValue=""
                        onChange={e => {
                          const targetAgentId = Number(e.target.value);
                          if (targetAgentId) runRedirect(q, w.channelUniqueId, { targetAgentId });
                          e.target.value = '';
                        }}
                        title="Direcionar para agente"
                      >
                        <option value="" disabled>Direcionar p/ agente...</option>
                        {agents.map(a => (
                          <option key={a.id} value={a.id}>{a.name}</option>
                        ))}
                      </select>
                    </div>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );

  const content = (
    <div className={wallboard ? 'page-body' : undefined}>
      {error && <div className="alert alert-error" style={{ marginBottom: 16 }}>{error}</div>}

      <div className="table-wrapper" style={{ marginBottom: 24 }}>
        <table>
          <thead>
            <tr><th>Fila</th><th>Em espera</th><th>Maior espera</th><th>Atendidas hoje</th><th>Abandonadas hoje</th><th>Nível de serviço</th></tr>
          </thead>
          <tbody>
            {snapshot.queues.flatMap(renderQueueRows)}
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
