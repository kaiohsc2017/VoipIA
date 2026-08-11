import { useEffect, useState } from 'react';
import { Plus, Play, Pause, Pencil, Trash2, FileText } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { StatusBadge } from './StatusBadge';
import { ConfirmModal } from './ConfirmModal';
import { AgentForm } from './AgentForm';
import { AgentLogModal } from './AgentLogModal';
import type { Agent, AgentFormData, PaginatedResponse, ServerEntry } from '../api/types';

const TYPE_LABEL: Record<Agent['type'], string> = {
  ssh_test: 'SSH Test',
  web_monitor: 'Web Monitor',
  log_monitor: 'Log Monitor',
  database: 'Database',
};

export function AgentsTab({ servers, canWrite }: { servers: ServerEntry[]; canWrite: boolean }) {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<Agent | null>(null);
  const [logsFor, setLogsFor] = useState<string | null>(null);
  const [confirmAgent, setConfirmAgent] = useState<Agent | null>(null);
  const [msg, setMsg] = useState('');

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    setLoading(true);
    api.get<PaginatedResponse<Agent> | Agent[]>('/api/agents/?limit=200')
      .then(({ data }) => setAgents(Array.isArray(data) ? data : data.items))
      .catch(() => setAgents([]))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const save = (data: AgentFormData) => {
    const req = editing
      ? api.put<Agent>(`/api/agents/${editing.id}`, data).then(({ data: updated }) => {
          if (updated?.id) setAgents(list => list.map(x => x.id === updated.id ? updated : x));
          else load();
        })
      : api.post<Agent>('/api/agents/', data).then(({ data: created }) => {
          if (created?.id) setAgents(list => [...list, created]);
          else load();
        });
    req.catch(err => flash(getErrorMessage(err, 'Erro ao salvar agente.')));
    setShowForm(false); setEditing(null);
  };

  const del = (id: string) => {
    api.delete(`/api/agents/${id}`)
      .then(() => setAgents(list => list.filter(x => x.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover agente.')));
  };

  const toggle = (a: Agent) => {
    const path = a.status === 'paused' ? `/api/agents/${a.id}/resume` : `/api/agents/${a.id}/pause`;
    api.post(path, {}).then(load).catch(err => flash(getErrorMessage(err, 'Erro ao alterar status.')));
  };

  const runNow = (id: string) => {
    api.post(`/api/agents/${id}/run`, {}).then(load).catch(err => flash(getErrorMessage(err, 'Erro ao executar agente.')));
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Agentes</h1><p>Automações de monitoramento e diagnóstico</p></div>
          {canWrite && <button className="btn btn-primary" onClick={() => { setEditing(null); setShowForm(true); }}><Plus size={14} /> Novo agente</button>}
        </div>
      </div>
      <div className="page-body">
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmAgent && (
          <ConfirmModal
            message={`Excluir "${confirmAgent.name}"?`}
            onConfirm={() => { del(confirmAgent.id); setConfirmAgent(null); }}
            onCancel={() => setConfirmAgent(null)}
          />
        )}
        {canWrite && (showForm || editing) && (
          <AgentForm agent={editing} servers={servers} onSave={save} onClose={() => { setShowForm(false); setEditing(null); }} />
        )}
        {logsFor && <AgentLogModal agentId={logsFor} onClose={() => setLogsFor(null)} />}

        <div className="card">
          <div className="table-wrapper">
            <table>
              <thead>
                <tr><th>Nome</th><th>Tipo</th><th>Status</th><th>Última / Próxima</th><th>Agendamento</th><th>Ações</th></tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={6} className="table-empty">Carregando...</td></tr>
                ) : agents.length === 0 ? (
                  <tr><td colSpan={6} className="table-empty">Nenhum agente cadastrado</td></tr>
                ) : agents.map(a => {
                  const schedLabel = a.schedule.type === 'always' ? 'Sempre ativo'
                    : a.schedule.type === 'interval' ? `A cada ${a.schedule.value || '?'}`
                    : a.schedule.value || '—';
                  const nextRun = a.next_run ? new Date(a.next_run) : null;
                  const diffMin = nextRun ? Math.round((nextRun.getTime() - Date.now()) / 60000) : null;
                  const nextLabel = diffMin !== null ? (diffMin <= 0 ? 'agora' : `em ${diffMin}min`) : null;
                  return (
                    <tr key={a.id}>
                      <td>
                        <div style={{ fontWeight: 500 }}>{a.name}</div>
                        {a.description && <div className="td-muted" style={{ fontSize: '0.72rem' }}>{a.description}</div>}
                      </td>
                      <td><span className="badge badge-info">{TYPE_LABEL[a.type] ?? a.type}</span></td>
                      <td><StatusBadge status={a.status} /></td>
                      <td className="td-muted" style={{ fontSize: '0.82rem' }}>
                        {a.last_run ? new Date(a.last_run).toLocaleString('pt-BR') : '—'}
                        {nextLabel && <div style={{ color: 'var(--clr-success)', fontSize: '0.72rem', marginTop: 2 }}>↻ {nextLabel}</div>}
                      </td>
                      <td style={{ fontSize: '0.82rem' }}>{schedLabel}</td>
                      <td>
                        <div className="flex gap-1">
                          {canWrite && <button className="btn btn-sm btn-primary" title="Executar agora" onClick={() => runNow(a.id)}><Play size={12} /></button>}
                          <button className="btn btn-sm btn-ghost" title="Logs" onClick={() => setLogsFor(a.id)}><FileText size={12} /></button>
                          {canWrite && <button className="btn btn-sm btn-ghost" title="Editar" onClick={() => { setEditing(a); setShowForm(true); }}><Pencil size={12} /></button>}
                          {canWrite && (
                            <button className={`btn btn-sm ${a.status === 'paused' ? 'btn-ghost' : 'btn-ghost'}`} title={a.status === 'paused' ? 'Retomar' : 'Pausar'} onClick={() => toggle(a)}>
                              {a.status === 'paused' ? <Play size={12} /> : <Pause size={12} />}
                            </button>
                          )}
                          {canWrite && <button className="btn btn-sm btn-danger" title="Excluir" onClick={() => setConfirmAgent(a)}><Trash2 size={12} /></button>}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}
