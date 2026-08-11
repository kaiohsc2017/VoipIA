import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { LlmConfigForm, LlmProvider, LlmStatus, LlmTestResult } from '../api/types';

/**
 * Config. IA — só usuários com escrita em `agents.llm` conseguem editar (o
 * próprio backend só libera `GET /api/llm/config/full` — valores em texto
 * puro — para quem tem escrita; leitura vê só `/api/llm/config`, mascarado).
 * Correção de gating confirmada: o app legado sempre mostrava o formulário e
 * os botões de salvar/testar, mesmo para quem só tinha leitura.
 */
export function LlmSettingsTab({ canWrite }: { canWrite: boolean }) {
  const [status, setStatus] = useState<LlmStatus | null>(null);
  const [providers, setProviders] = useState<LlmProvider[]>([]);
  const [form, setForm] = useState<LlmConfigForm | null>(null);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<LlmTestResult | null>(null);
  const [testing, setTesting] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    api.get<LlmStatus>('/api/llm/status').then(({ data }) => setStatus(data)).catch(() => {});
    api.get<{ providers: LlmProvider[] }>('/api/llm/providers').then(({ data }) => setProviders(data.providers || [])).catch(() => {});
    const configPath = canWrite ? '/api/llm/config/full' : '/api/llm/config';
    api.get<{ values: LlmConfigForm }>(configPath).then(({ data }) => setForm(data.values || {})).catch(() => setForm({}));
  }, [canWrite]);

  const setF = (k: string, v: string) => setForm(f => (f ? { ...f, [k]: v } : f));

  const save = () => {
    if (!form) return;
    setSaving(true); setMsg(null);
    api.post<{ ok: boolean; status: LlmStatus; detail?: string }>('/api/llm/config', form)
      .then(({ data }) => {
        if (data.ok) { setMsg({ ok: true, text: 'Configuração salva com sucesso!' }); setStatus(data.status); }
        else setMsg({ ok: false, text: data.detail || 'Erro ao salvar.' });
      })
      .catch(err => setMsg({ ok: false, text: getErrorMessage(err, 'Erro ao salvar.') }))
      .finally(() => setSaving(false));
  };

  const runTest = () => {
    setTesting(true); setTestResult(null);
    api.post<LlmTestResult>('/api/llm/test', {})
      .then(({ data }) => setTestResult(data))
      .catch(err => setTestResult({ ok: false, error: getErrorMessage(err, 'Falha ao testar.') }))
      .finally(() => setTesting(false));
  };

  const currentProvider = providers.find(p => status && p.id === status.provider);

  if (!form) {
    return (
      <>
        <div className="page-header"><h1>Configuração de IA</h1></div>
        <div className="page-body"><div className="loading-state"><div className="spinner" />Carregando...</div></div>
      </>
    );
  }

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Configuração de IA</h1><p>Provedor, modelo e credenciais usados pelos agentes</p></div>
          {canWrite && (
            <div className="flex gap-1">
              <button className="btn btn-ghost btn-sm" onClick={runTest} disabled={testing}>{testing ? 'Testando...' : 'Testar conexão'}</button>
              <button className="btn btn-primary btn-sm" onClick={save} disabled={saving}>{saving ? 'Salvando...' : 'Salvar configuração'}</button>
            </div>
          )}
        </div>
      </div>
      <div className="page-body">
        {msg && (
          <div className="flash-message" style={!msg.ok ? { background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' } : undefined}>
            {msg.text}
          </div>
        )}

        {testResult && (
          <div className="flash-message" style={!testResult.ok ? { background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' } : undefined}>
            {testResult.ok ? (
              <div>
                <div style={{ fontWeight: 500, marginBottom: 4 }}>✓ Conectado: {testResult.provider} / {testResult.model}</div>
                <div style={{ fontFamily: 'monospace', fontSize: 11, opacity: 0.8 }}>{(testResult.response ?? '').slice(0, 200)}...</div>
              </div>
            ) : (
              <div>
                <div style={{ fontWeight: 500, marginBottom: 2 }}>✗ Falhou</div>
                <div style={{ fontSize: 12, fontFamily: 'monospace' }}>{testResult.error}</div>
              </div>
            )}
          </div>
        )}

        <div className="card" style={{ marginBottom: 14 }}>
          <div className="card-header">
            <span className="card-title">Status atual</span>
            {status && <span className={`badge ${status.ready ? 'badge-success' : 'badge-danger'}`}>{status.ready ? 'Pronto' : `Inativo${status.reason ? `: ${status.reason}` : ''}`}</span>}
          </div>
          <div className="card-body" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <div><div className="text-muted" style={{ fontSize: 11 }}>Provedor</div><div style={{ fontWeight: 500, marginTop: 2 }}>{status?.provider || '—'}</div></div>
            <div><div className="text-muted" style={{ fontSize: 11 }}>Modelo</div><div style={{ fontWeight: 500, marginTop: 2 }}>{status?.model || '—'}</div></div>
            <div>
              <div className="text-muted" style={{ fontSize: 11 }}>IA habilitada</div>
              <div style={{ marginTop: 2 }}><span className={`badge ${status?.enabled ? 'badge-success' : 'badge-gray'}`}>{status?.enabled ? 'Sim (opt-in ativo)' : 'Não — desabilitada'}</span></div>
            </div>
            <div>
              <div className="text-muted" style={{ fontSize: 11 }}>Arquivo de config</div>
              <div className="mono" style={{ fontSize: 11, marginTop: 2, color: 'var(--text-secondary)' }}>
                {status?.env_file || '—'}
                {status && !status.file_exists && <span style={{ color: 'var(--clr-warning)', marginLeft: 6 }}>(será criado ao salvar)</span>}
              </div>
            </div>
          </div>
        </div>

        {!canWrite ? (
          <div className="card"><div className="card-body table-empty">Você não tem permissão de escrita para editar a Configuração de IA.</div></div>
        ) : (
          <>
            <div className="card" style={{ marginBottom: 14 }}>
              <div className="card-header"><span className="card-title">Configuração geral</span></div>
              <div className="card-body">
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">Habilitar IA</label>
                    <select className="form-select" value={form.AGENTS_LLM_ENABLED || 'false'} onChange={e => setF('AGENTS_LLM_ENABLED', e.target.value)}>
                      <option value="false">Não — desabilitada (padrão)</option>
                      <option value="true">Sim — habilitada</option>
                    </select>
                    <div className="hint">Quando desabilitada, nenhuma chamada à IA é feita — zero custo.</div>
                  </div>
                  <div className="form-group">
                    <label className="form-label">Provedor ativo</label>
                    <select className="form-select" value={form.AGENTS_LLM_PROVIDER || 'google'} onChange={e => setF('AGENTS_LLM_PROVIDER', e.target.value)}>
                      <option value="google">Google Gemini</option>
                      <option value="anthropic">Anthropic Claude</option>
                      <option value="openai">OpenAI GPT</option>
                      <option value="minimax">MiniMax</option>
                      <option value="openai_compat">API Compatível OpenAI (Ollama / Groq / Together)</option>
                    </select>
                  </div>
                </div>
                <div className="form-group">
                  <label className="form-label">Modelo ativo</label>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <input className="form-input" value={form.AGENTS_LLM_MODEL || ''} onChange={e => setF('AGENTS_LLM_MODEL', e.target.value)} placeholder="gemini-2.5-flash" />
                    {currentProvider && (
                      <select className="form-select" style={{ width: 220, flexShrink: 0 }} value="" onChange={e => { if (e.target.value) setF('AGENTS_LLM_MODEL', e.target.value); }}>
                        <option value="">Modelos populares...</option>
                        {currentProvider.models.map(m => <option key={m} value={m}>{m}</option>)}
                      </select>
                    )}
                  </div>
                  <div className="hint">Digite o nome exato do modelo ou escolha um sugerido ao lado.</div>
                </div>
              </div>
            </div>

            <div className="card" style={{ marginBottom: 14 }}>
              <div className="card-header"><span className="card-title">API Keys</span></div>
              <div className="card-body">
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">Google Gemini</label>
                    <input className="form-input" type="password" value={form.AGENTS_LLM_GOOGLE_KEY || ''} onChange={e => setF('AGENTS_LLM_GOOGLE_KEY', e.target.value)} placeholder="AIza..." />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Anthropic Claude</label>
                    <input className="form-input" type="password" value={form.AGENTS_LLM_ANTHROPIC_KEY || ''} onChange={e => setF('AGENTS_LLM_ANTHROPIC_KEY', e.target.value)} placeholder="sk-ant-..." />
                  </div>
                </div>
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">OpenAI GPT</label>
                    <input className="form-input" type="password" value={form.AGENTS_LLM_OPENAI_KEY || ''} onChange={e => setF('AGENTS_LLM_OPENAI_KEY', e.target.value)} placeholder="sk-..." />
                  </div>
                  <div className="form-group">
                    <label className="form-label">MiniMax Key</label>
                    <input className="form-input" type="password" value={form.AGENTS_LLM_MINIMAX_KEY || ''} onChange={e => setF('AGENTS_LLM_MINIMAX_KEY', e.target.value)} placeholder="..." />
                  </div>
                </div>
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">MiniMax Group ID</label>
                    <input className="form-input" value={form.AGENTS_LLM_MINIMAX_GROUP_ID || ''} onChange={e => setF('AGENTS_LLM_MINIMAX_GROUP_ID', e.target.value)} placeholder="..." />
                  </div>
                  <div className="form-group" />
                </div>
              </div>
            </div>

            <div className="card" style={{ marginBottom: 14 }}>
              <div className="card-header">
                <span className="card-title">API Compatível OpenAI</span>
                <span className="text-muted" style={{ fontSize: '0.8rem' }}>Ollama, Groq, Together, LM Studio...</span>
              </div>
              <div className="card-body">
                <div className="form-grid">
                  <div className="form-group">
                    <label className="form-label">URL base</label>
                    <input className="form-input" value={form.AGENTS_LLM_COMPAT_URL || ''} onChange={e => setF('AGENTS_LLM_COMPAT_URL', e.target.value)} placeholder="http://localhost:11434/v1" />
                  </div>
                  <div className="form-group">
                    <label className="form-label">API Key (opcional)</label>
                    <input className="form-input" type="password" value={form.AGENTS_LLM_COMPAT_KEY || ''} onChange={e => setF('AGENTS_LLM_COMPAT_KEY', e.target.value)} placeholder="ollama ou sk-..." />
                  </div>
                </div>
                <div className="hint">Para usar Ollama local no VPS: URL = http://ollama:11434/v1 | Key = ollama | Modelo = llama3.2</div>
              </div>
            </div>

            <div style={{ textAlign: 'right', paddingBottom: 24 }}>
              <button className="btn btn-ghost btn-sm" onClick={runTest} disabled={testing} style={{ marginRight: 8 }}>{testing ? 'Testando...' : 'Testar conexão'}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? 'Salvando...' : 'Salvar configuração'}</button>
            </div>
          </>
        )}
      </div>
    </>
  );
}
