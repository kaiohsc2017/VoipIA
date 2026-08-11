import { useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { Agent, AgentSecret, PaginatedResponse } from '../api/types';

export function SecretsTab({ canWrite }: { canWrite: boolean }) {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [selAgent, setSelAgent] = useState('');
  const [secrets, setSecrets] = useState<AgentSecret[]>([]);
  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState('');
  const [form, setForm] = useState({ key: '', value: '' });
  const [confirmKey, setConfirmKey] = useState<string | null>(null);

  useEffect(() => {
    api.get<PaginatedResponse<Agent> | Agent[]>('/api/agents/?limit=200')
      .then(({ data }) => setAgents(Array.isArray(data) ? data : data.items))
      .catch(() => setAgents([]));
  }, []);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 3000); };

  const loadSecrets = (agentId: string) => {
    if (!agentId) return;
    setLoading(true);
    api.get<AgentSecret[]>(`/api/system/agents/${agentId}/secrets`)
      .then(({ data }) => setSecrets(Array.isArray(data) ? data : []))
      .catch(() => setSecrets([]))
      .finally(() => setLoading(false));
  };

  const selectAgent = (agentId: string) => {
    setSelAgent(agentId);
    setSecrets([]);
    setForm({ key: '', value: '' });
    loadSecrets(agentId);
  };

  const save = () => {
    if (!form.key || !form.value) { flash('Preencha chave e valor.'); return; }
    api.post(`/api/system/agents/${selAgent}/secrets`, form)
      .then(({ data }) => {
        if ((data as { ok?: boolean })?.ok) {
          flash('Secret salvo.');
          setForm({ key: '', value: '' });
          loadSecrets(selAgent);
        } else {
          flash('Erro ao salvar.');
        }
      })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar.')));
  };

  const del = (key: string) => {
    api.delete(`/api/system/agents/${selAgent}/secrets/${encodeURIComponent(key)}`)
      .then(() => loadSecrets(selAgent))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover.')));
  };

  return (
    <>
      <div className="page-header"><h1>Secrets por Agente</h1><p>Variáveis sensíveis referenciadas via <code>{'{{NOME_DA_CHAVE}}'}</code> nos comandos e queries</p></div>
      <div className="page-body">
        {confirmKey && (
          <ConfirmModal
            message={`Remover secret "${confirmKey}"?`}
            onConfirm={() => { del(confirmKey); setConfirmKey(null); }}
            onCancel={() => setConfirmKey(null)}
          />
        )}

        <div className="card" style={{ marginBottom: 16 }}>
          <div className="card-header"><span className="card-title">Sobre Secrets</span></div>
          <div className="card-body">
            <p style={{ fontSize: '0.855rem', color: 'var(--text-secondary)', margin: 0 }}>
              Secrets são variáveis sensíveis armazenadas com segurança por agente. Use a sintaxe{' '}
              <code style={{ background: 'var(--bg-input)', padding: '2px 5px', borderRadius: 4 }}>{'{{NOME_DA_CHAVE}}'}</code>{' '}
              nos comandos e queries do agente para referenciar o valor em tempo de execução.
            </p>
          </div>
        </div>

        <div className="card" style={{ marginBottom: 16 }}>
          <div className="card-header"><span className="card-title">Selecionar agente</span></div>
          <div className="card-body">
            <div className="form-group">
              <select className="form-select" aria-label="Selecione o agente" value={selAgent} onChange={e => selectAgent(e.target.value)}>
                <option value="">Selecione um agente...</option>
                {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </div>
          </div>
        </div>

        {selAgent && canWrite && (
          <div className="card" style={{ marginBottom: 16 }}>
            <div className="card-header"><span className="card-title">Adicionar secret</span></div>
            <div className="card-body">
              {msg && <div className="flash-message">{msg}</div>}
              <div className="form-grid" style={{ alignItems: 'flex-end' }}>
                <div className="form-group">
                  <label className="form-label">Chave</label>
                  <input className="form-input" aria-label="Chave" value={form.key} placeholder="DB_PASSWORD"
                    onChange={e => setForm(f => ({ ...f, key: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Valor</label>
                  <input className="form-input" aria-label="Valor" type="password" value={form.value} placeholder="valor secreto"
                    onChange={e => setForm(f => ({ ...f, value: e.target.value }))} />
                </div>
              </div>
              <button className="btn btn-primary" onClick={save}><Plus size={13} /> Salvar</button>
            </div>
          </div>
        )}

        {selAgent && (
          <div className="card">
            <div className="card-header">
              <span className="card-title">Secrets cadastrados</span>
              <span className="text-muted" style={{ fontSize: '0.8rem' }}>{secrets.length} secret{secrets.length !== 1 ? 's' : ''}</span>
            </div>
            <div className="table-wrapper">
              <table>
                <thead><tr><th>Chave</th><th>Criado em</th><th /></tr></thead>
                <tbody>
                  {loading ? (
                    <tr><td colSpan={3} className="table-empty">Carregando...</td></tr>
                  ) : secrets.length === 0 ? (
                    <tr><td colSpan={3} className="table-empty">Nenhum secret para este agente</td></tr>
                  ) : secrets.map(s => (
                    <tr key={s.id ?? s.key}>
                      <td><code style={{ fontSize: 12, background: 'var(--bg-input)', padding: '2px 6px', borderRadius: 4 }}>{s.key}</code></td>
                      <td className="td-muted">{new Date(s.created_at).toLocaleString('pt-BR')}</td>
                      <td>
                        {canWrite && (
                          <button className="btn btn-sm btn-danger" onClick={() => setConfirmKey(s.key)}><Trash2 size={12} /></button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
