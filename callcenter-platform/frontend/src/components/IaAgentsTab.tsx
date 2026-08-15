import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { IaAgent, IaAgentRequest, CcQueue } from '../api/types';

const MODELS = ['gemini-2.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.5-pro'];

const EMPTY: IaAgentRequest = {
  name: '', description: '', systemPrompt: '', greeting: '', model: MODELS[0],
  temperature: 0.2, topK: null, matchThreshold: null, kbTags: '', maxTurns: 5,
  maxCostUsd: 0.1, fallbackQueueId: null,
};

export function IaAgentsTab({ canWrite }: { canWrite: boolean }) {
  const [agents, setAgents] = useState<IaAgent[]>([]);
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<IaAgent | null>(null);
  const [confirmAgent, setConfirmAgent] = useState<IaAgent | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<IaAgentRequest>(EMPTY);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const load = () => {
    api.get<IaAgent[]>('/callcenter/ia-agents').then(({ data }) => setAgents(data)).catch(() => setAgents([]));
  };
  useEffect(load, []);
  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => setQueues([]));
  }, []);

  const openForm = (a: IaAgent | null) => {
    setEditing(a);
    setFd(a ? {
      name: a.name, description: a.description, systemPrompt: a.systemPrompt, greeting: a.greeting,
      model: a.model, temperature: a.temperature, topK: a.topK, matchThreshold: a.matchThreshold,
      kbTags: a.kbTags, maxTurns: a.maxTurns, maxCostUsd: a.maxCostUsd, fallbackQueueId: a.fallbackQueueId,
    } : EMPTY);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/ia-agents/${editing.id}`, fd)
      : api.post('/callcenter/ia-agents', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar agente de IA.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/ia-agents/${id}`)
      .then(() => load())
      .catch(err => flash(getErrorMessage(err, 'Erro ao desativar agente de IA.')));
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div>
            <h1>Agentes de IA</h1>
            <p>Persona/prompt/modelo consultados pelo nó "Agente de IA" do Flow Builder (voz e chat)</p>
          </div>
          {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Novo agente</button>}
        </div>
      </div>
      <div className="page-body">
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmAgent && (
          <ConfirmModal
            message={`Desativar o agente de IA "${confirmAgent.name}"? Fluxos que o usam passam a escalar direto para a fila de fallback.`}
            onConfirm={() => { del(confirmAgent.id); setConfirmAgent(null); }}
            onCancel={() => setConfirmAgent(null)}
          />
        )}
        {canWrite && showForm && (
          <div className="modal-overlay" onClick={() => setShowForm(false)}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>{editing ? 'Editar agente de IA' : 'Novo agente de IA'}</h2>
                <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
              </div>
              <div className="modal-body">
                <div className="form-group">
                  <label className="form-label">Nome</label>
                  <input className="form-input" value={fd.name} onChange={e => setFd(f => ({ ...f, name: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Descrição (opcional)</label>
                  <input className="form-input" value={fd.description ?? ''} onChange={e => setFd(f => ({ ...f, description: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Persona / instruções de sistema</label>
                  <textarea className="form-input" rows={6} value={fd.systemPrompt}
                    onChange={e => setFd(f => ({ ...f, systemPrompt: e.target.value }))}
                    placeholder="Ex: Você é um assistente cordial e objetivo do suporte de TI." />
                </div>
                <div className="form-group">
                  <label className="form-label">Saudação inicial (opcional)</label>
                  <input className="form-input" value={fd.greeting ?? ''} onChange={e => setFd(f => ({ ...f, greeting: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Modelo</label>
                  <select className="form-select" value={fd.model} onChange={e => setFd(f => ({ ...f, model: e.target.value }))}>
                    {MODELS.map(m => <option key={m} value={m}>{m}</option>)}
                  </select>
                </div>
                <div className="form-group">
                  <label className="form-label">Temperatura (0-2)</label>
                  <input type="number" min={0} max={2} step={0.05} className="form-input" style={{ maxWidth: 120 }}
                    value={fd.temperature} onChange={e => setFd(f => ({ ...f, temperature: Number(e.target.value) }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Top K (opcional — herda o padrão global se vazio)</label>
                  <input type="number" min={1} className="form-input" style={{ maxWidth: 120 }}
                    value={fd.topK ?? ''} onChange={e => setFd(f => ({ ...f, topK: e.target.value ? Number(e.target.value) : null }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Limiar de similaridade (opcional — herda o padrão global se vazio)</label>
                  <input type="number" min={0} max={1} step={0.01} className="form-input" style={{ maxWidth: 120 }}
                    value={fd.matchThreshold ?? ''} onChange={e => setFd(f => ({ ...f, matchThreshold: e.target.value ? Number(e.target.value) : null }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Tags da base de conhecimento (opcional)</label>
                  <input className="form-input" value={fd.kbTags ?? ''} onChange={e => setFd(f => ({ ...f, kbTags: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Máximo de turnos por atendimento</label>
                  <input type="number" min={1} max={20} className="form-input" style={{ maxWidth: 120 }}
                    value={fd.maxTurns} onChange={e => setFd(f => ({ ...f, maxTurns: Number(e.target.value) }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Custo máximo por atendimento (USD)</label>
                  <input type="number" min={0} step={0.01} className="form-input" style={{ maxWidth: 120 }}
                    value={fd.maxCostUsd} onChange={e => setFd(f => ({ ...f, maxCostUsd: Number(e.target.value) }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Fila de fallback (escalação)</label>
                  <select className="form-select" value={fd.fallbackQueueId ?? ''}
                    onChange={e => setFd(f => ({ ...f, fallbackQueueId: e.target.value ? Number(e.target.value) : null }))}>
                    <option value="">— nenhuma —</option>
                    {queues.map(q => <option key={q.id} value={q.id}>{q.displayName}</option>)}
                  </select>
                </div>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
                <button className="btn btn-primary" onClick={save} disabled={!fd.name.trim() || !fd.systemPrompt.trim()}>Salvar</button>
              </div>
            </div>
          </div>
        )}
        <div className="table-wrapper">
          <table>
            <thead>
              <tr><th>Nome</th><th>Modelo</th><th>Máx. turnos</th><th>Fila de fallback</th><th>Ativo</th>{canWrite && <th></th>}</tr>
            </thead>
            <tbody>
              {agents.map(a => (
                <tr key={a.id}>
                  <td>{a.name}</td>
                  <td>{a.model}</td>
                  <td>{a.maxTurns}</td>
                  <td>{a.fallbackQueueName ?? '—'}</td>
                  <td>{a.active ? '✅' : '—'}</td>
                  {canWrite && (
                    <td>
                      <button className="btn btn-ghost btn-sm" onClick={() => openForm(a)}><Pencil size={14} /></button>
                      {a.active && <button className="btn btn-ghost btn-sm" onClick={() => setConfirmAgent(a)}><Trash2 size={14} /></button>}
                    </td>
                  )}
                </tr>
              ))}
              {agents.length === 0 && (
                <tr><td colSpan={canWrite ? 6 : 5} className="table-empty">Nenhum agente de IA cadastrado.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
