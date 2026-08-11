import type { AgentStatus } from '../api/types';

const TONE: Record<AgentStatus, string> = {
  idle: 'badge-gray',
  running: 'badge-info',
  success: 'badge-success',
  error: 'badge-danger',
  partial: 'badge-warning',
  paused: 'badge-gray',
};

const LABEL: Record<AgentStatus, string> = {
  idle: 'Aguardando',
  running: 'Executando',
  success: 'Sucesso',
  error: 'Erro',
  partial: 'Parcial',
  paused: 'Pausado',
};

export function StatusBadge({ status }: { status: AgentStatus }) {
  return <span className={`badge ${TONE[status] ?? 'badge-gray'}`}>{LABEL[status] ?? status}</span>;
}
