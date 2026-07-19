import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import type { Agent, AgentCheck, AgentFormData, ServerEntry } from '../api/types';

const EMPTY_FORM: AgentFormData = {
  name: '', type: 'ssh_test', skill: '', server_ids: [], target_urls: [],
  rules: { checks: [] },
  schedule: { type: 'interval', value: '5m', active: true },
  notify_telegram: false, telegram_chat: '',
  notify_email: false, notify_email_to: '',
  notify_webhook: false, notify_webhook_url: '',
};

const EMPTY_CHECK: AgentCheck = { name: '', cmd: '', expect: '', fix_hint: '' };

interface AgentFormProps {
  agent: Agent | null;
  servers: ServerEntry[];
  onSave: (data: AgentFormData) => void;
  onClose: () => void;
}

export function AgentForm({ agent, servers, onSave, onClose }: AgentFormProps) {
  const [form, setForm] = useState<AgentFormData>(agent ? { ...EMPTY_FORM, ...agent } : EMPTY_FORM);
  const [chk, setChk] = useState<AgentCheck>(EMPTY_CHECK);
  const [urlVal, setUrlVal] = useState('');

  const setF = <K extends keyof AgentFormData>(k: K, v: AgentFormData[K]) => setForm(f => ({ ...f, [k]: v }));
  const setSch = (k: keyof AgentFormData['schedule'], v: string) => setForm(f => ({ ...f, schedule: { ...f.schedule, [k]: v } }));

  const addCheck = () => {
    if (!chk.cmd) return;
    setF('rules', { ...form.rules, checks: [...(form.rules.checks ?? []), chk] });
    setChk(EMPTY_CHECK);
  };
  const delCheck = (i: number) => {
    const checks = [...(form.rules.checks ?? [])];
    checks.splice(i, 1);
    setF('rules', { ...form.rules, checks });
  };
  const addUrl = () => {
    if (!urlVal) return;
    setF('target_urls', [...(form.target_urls ?? []), urlVal]);
    setUrlVal('');
  };
  const delUrl = (i: number) => {
    const urls = [...(form.target_urls ?? [])];
    urls.splice(i, 1);
    setF('target_urls', urls);
  };
  const toggleServer = (id: string) => {
    const cur = form.server_ids ?? [];
    setF('server_ids', cur.includes(id) ? cur.filter(x => x !== id) : [...cur, id]);
  };

  return (
    <div className="modal-overlay">
      <div className="modal modal-lg">
        <div className="modal-header">
          <h2>{agent ? 'Editar Agente' : 'Novo Agente'}</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          <div className="form-grid">
            <div className="form-group">
              <label className="form-label">Nome</label>
              <input className="form-input" value={form.name} onChange={e => setF('name', e.target.value)} placeholder="Ex: Monitor Nginx Prod" />
            </div>
            <div className="form-group">
              <label className="form-label">Tipo</label>
              <select className="form-select" value={form.type} onChange={e => setF('type', e.target.value as AgentFormData['type'])}>
                <option value="ssh_test">Teste SSH</option>
                <option value="web_monitor">Monitor Web</option>
                <option value="log_monitor">Monitor de Logs</option>
                <option value="database">Database</option>
              </select>
            </div>
          </div>

          <div className="form-group">
            <label className="form-label">Skill / Contexto</label>
            <textarea className="form-textarea" rows={3} value={form.skill ?? ''} onChange={e => setF('skill', e.target.value)}
              placeholder="Ex: Especialista em Asterisk 21. Verificar SIP, AudioSocket e canais. Em falha, reiniciar serviço afetado." />
            <div className="hint">Contexto do especialista. Será usado como guia e consultado pela IA em casos não cobertos.</div>
          </div>

          {form.type === 'ssh_test' && (
            <div>
              <div className="form-group">
                <label className="form-label">Servidores alvo</label>
                <div style={{ display: 'flex', flexWrap: 'wrap', padding: 8, background: 'var(--bg-input)', borderRadius: 6, border: '1px solid var(--border-glass)' }}>
                  {servers.length === 0 ? (
                    <span className="text-muted" style={{ fontSize: '0.72rem' }}>Nenhum servidor cadastrado.</span>
                  ) : servers.map(s => (
                    <label key={s.id} style={{
                      display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 13,
                      padding: '4px 8px', background: 'var(--bg-card)', borderRadius: 4,
                      border: '1px solid var(--border-glass)', marginRight: 6, marginBottom: 4,
                    }}>
                      <input type="checkbox" checked={(form.server_ids ?? []).includes(s.id)} onChange={() => toggleServer(s.id)} />
                      {s.name} ({s.host})
                    </label>
                  ))}
                </div>
              </div>

              <hr className="divider" />
              <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>Verificações</div>

              <div style={{ background: 'var(--bg-input)', borderRadius: 6, padding: 12, marginBottom: 10 }}>
                <div className="form-grid">
                  <div className="form-group" style={{ marginBottom: 8 }}>
                    <label className="form-label">Nome</label>
                    <input className="form-input" value={chk.name ?? ''} placeholder="nginx rodando" onChange={e => setChk(c => ({ ...c, name: e.target.value }))} />
                  </div>
                  <div className="form-group" style={{ marginBottom: 8 }}>
                    <label className="form-label">Comando</label>
                    <input className="form-input" value={chk.cmd} placeholder="systemctl is-active nginx" onChange={e => setChk(c => ({ ...c, cmd: e.target.value }))} />
                  </div>
                </div>
                <div className="form-grid">
                  <div className="form-group" style={{ marginBottom: 8 }}>
                    <label className="form-label">Saída esperada</label>
                    <input className="form-input" value={chk.expect ?? ''} placeholder="active" onChange={e => setChk(c => ({ ...c, expect: e.target.value }))} />
                  </div>
                  <div className="form-group" style={{ marginBottom: 8 }}>
                    <label className="form-label">Dica de correção</label>
                    <input className="form-input" value={chk.fix_hint ?? ''} placeholder="sudo systemctl restart nginx" onChange={e => setChk(c => ({ ...c, fix_hint: e.target.value }))} />
                  </div>
                </div>
                <button className="btn btn-primary btn-sm" onClick={addCheck}><Plus size={12} /> Adicionar</button>
              </div>

              {(form.rules.checks ?? []).map((c, i) => (
                <div key={i} style={{
                  display: 'flex', alignItems: 'flex-start', gap: 8, padding: '8px 12px',
                  background: 'var(--bg-card)', border: '1px solid var(--border-glass)', borderRadius: 6, marginBottom: 6,
                }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 500, fontSize: '0.855rem' }}>{c.name || c.cmd}</div>
                    <div className="text-muted mono" style={{ fontSize: '0.72rem' }}>{c.cmd}</div>
                    {c.expect && <div style={{ fontSize: '0.72rem', color: 'var(--clr-success)' }}>esperado: {c.expect}</div>}
                    {c.fix_hint && <div style={{ fontSize: '0.72rem', color: 'var(--clr-warning)' }}>fix: {c.fix_hint}</div>}
                  </div>
                  <button className="btn btn-sm btn-danger" onClick={() => delCheck(i)}><Trash2 size={11} /></button>
                </div>
              ))}
            </div>
          )}

          {form.type === 'web_monitor' && (
            <div className="form-group">
              <label className="form-label">URLs a monitorar</label>
              <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
                <input className="form-input" value={urlVal} placeholder="https://app.voiphash.com.br"
                  onChange={e => setUrlVal(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') addUrl(); }} />
                <button className="btn btn-primary btn-sm" onClick={addUrl}><Plus size={12} /></button>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {(form.target_urls ?? []).map((u, i) => (
                  <span key={i} className="chip" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                    {u}
                    <span style={{ cursor: 'pointer', color: 'var(--clr-danger)' }} onClick={() => delUrl(i)}>×</span>
                  </span>
                ))}
              </div>
            </div>
          )}

          <hr className="divider" />
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>Inteligência Artificial</div>
          <div style={{ background: 'var(--bg-input)', borderRadius: 6, padding: '12px 14px', marginBottom: 14, border: '1px solid var(--border-glass)' }}>
            <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, cursor: 'pointer', fontSize: 13 }}>
              <input type="checkbox" style={{ marginTop: 2, flexShrink: 0 }} checked={!!form.rules.use_ai_on_failure}
                onChange={e => setF('rules', { ...form.rules, use_ai_on_failure: e.target.checked })} />
              <div>
                <div style={{ fontWeight: 500, marginBottom: 2 }}>Ativar IA neste agente</div>
                <div className="text-muted" style={{ fontSize: '0.72rem' }}>
                  Quando uma verificação falhar sem solução configurada, o agente consultará a IA ativa em Configuração de IA. Requer IA habilitada globalmente.
                </div>
              </div>
            </label>
          </div>

          <hr className="divider" />
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>Agendamento</div>
          <div className="form-grid">
            <div className="form-group">
              <label className="form-label">Tipo</label>
              <select className="form-select" value={form.schedule.type} onChange={e => setSch('type', e.target.value)}>
                <option value="interval">Intervalo</option>
                <option value="cron">Cron</option>
                <option value="always">Sempre ativo</option>
                <option value="once">Executar uma vez</option>
              </select>
            </div>
            <div className="form-group">
              <label className="form-label">{form.schedule.type === 'cron' ? 'Expressão cron' : 'Intervalo'}</label>
              <input className="form-input" value={form.schedule.value ?? ''}
                placeholder={form.schedule.type === 'cron' ? '0 * * * *' : '5m / 1h / 30s'}
                onChange={e => setSch('value', e.target.value)} />
            </div>
          </div>

          <hr className="divider" />
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>Notificações de alerta</div>

          <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 13, marginBottom: 6 }}>
            <input type="checkbox" checked={!!form.notify_telegram} onChange={e => setF('notify_telegram', e.target.checked)} />
            Telegram
          </label>
          {form.notify_telegram && (
            <div className="form-group" style={{ marginBottom: 10 }}>
              <label className="form-label">Chat ID</label>
              <input className="form-input" value={form.telegram_chat ?? ''} onChange={e => setF('telegram_chat', e.target.value)} placeholder="-100123456789" />
            </div>
          )}

          <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 13, marginBottom: 6 }}>
            <input type="checkbox" checked={!!form.notify_email} onChange={e => setF('notify_email', e.target.checked)} />
            E-mail
          </label>
          {form.notify_email && (
            <div className="form-group" style={{ marginBottom: 10 }}>
              <label className="form-label">Destinatário</label>
              <input className="form-input" value={form.notify_email_to ?? ''} onChange={e => setF('notify_email_to', e.target.value)} placeholder="ops@empresa.com" />
            </div>
          )}

          <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 13, marginBottom: 6 }}>
            <input type="checkbox" checked={!!form.notify_webhook} onChange={e => setF('notify_webhook', e.target.checked)} />
            Webhook
          </label>
          {form.notify_webhook && (
            <div className="form-group" style={{ marginBottom: 10 }}>
              <label className="form-label">URL do webhook</label>
              <input className="form-input" value={form.notify_webhook_url ?? ''} onChange={e => setF('notify_webhook_url', e.target.value)} placeholder="https://hooks.slack.com/services/..." />
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" onClick={() => onSave(form)}>{agent ? 'Salvar' : 'Criar agente'}</button>
        </div>
      </div>
    </div>
  );
}
