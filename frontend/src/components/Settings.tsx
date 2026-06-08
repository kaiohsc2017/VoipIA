import { useEffect, useState, useRef } from 'react';
import api from '../api/client';

// ─── Tipos ───────────────────────────────────────────────────────────────────

interface SettingMeta {
  value: string;
  isSecret: boolean;
}

type Settings = Record<string, SettingMeta>;

interface Section {
  id: string;
  icon: string;
  title: string;
  description: string;
  keys: FieldDef[];
}

interface FieldDef {
  key: string;
  label: string;
  placeholder?: string;
  hint?: string;
  type?: 'text' | 'password' | 'number' | 'select';
  options?: { value: string; label: string }[];
}

// ─── Definição das Seções e Campos ───────────────────────────────────────────

const SECTIONS: Section[] = [
  {
    id: 'sip',
    icon: '🔌',
    title: 'Tronco SIP (Operadora)',
    description: 'Configurações da operadora de telefonia para receber e realizar chamadas.',
    keys: [
      { key: 'SIP_TRUNK_HOST',        label: 'Host / IP da Operadora',     placeholder: 'sip.operadora.com.br' },
      { key: 'SIP_TRUNK_USER',        label: 'Usuário do Tronco',          placeholder: 'usuario_tronco' },
      { key: 'SIP_TRUNK_PASSWORD',    label: 'Senha do Tronco',            type: 'password' },
      { key: 'SIP_TRUNK_FROM_DOMAIN', label: 'From Domain (SIP)',          placeholder: 'sip.operadora.com.br',
        hint: 'Geralmente igual ao Host. Usado no cabeçalho SIP From.' },
      { key: 'AST_OUTBOUND_TRUNK',    label: 'Nome do Trunk no Asterisk',  placeholder: 'tronco-sip',
        hint: 'Deve corresponder ao nome definido no pjsip.conf.' },
      { key: 'AST_OUTBOUND_CONTEXT',  label: 'Contexto de Discagem',       placeholder: 'discagem-sainte' },
    ],
  },
  {
    id: 'gemini',
    icon: '🤖',
    title: 'Google Gemini API',
    description: 'Chave e modelos usados pelo Agente de IA para transcrição (STT), raciocínio (LLM) e síntese de voz (TTS).',
    keys: [
      { key: 'GEMINI_API_KEY',      label: 'API Key',              type: 'password',
        hint: 'Obtenha em https://aistudio.google.com/app/apikey' },
      { key: 'GEMINI_MODEL_STT',    label: 'Modelo STT (Voz→Texto)',  placeholder: 'gemini-2.0-flash' },
      { key: 'GEMINI_MODEL_LLM',    label: 'Modelo LLM (Raciocínio)', placeholder: 'gemini-2.0-flash' },
      { key: 'GEMINI_MODEL_TTS',    label: 'Modelo TTS (Texto→Voz)',  placeholder: 'gemini-2.5-flash-preview-tts' },
    ],
  },
  {
    id: 'jira',
    icon: '🎫',
    title: 'Jira Cloud',
    description: 'Integração com o Jira Cloud para criação automática de chamados pela URA (Módulo 1).',
    keys: [
      { key: 'JIRA_BASE_URL',     label: 'URL da Instância Jira',   placeholder: 'https://empresa.atlassian.net' },
      { key: 'JIRA_USER_EMAIL',   label: 'E-mail do Usuário Jira',  placeholder: 'usuario@empresa.com' },
      { key: 'JIRA_API_TOKEN',    label: 'API Token Jira',          type: 'password',
        hint: 'Gere em https://id.atlassian.com/manage-profile/security/api-tokens' },
      { key: 'JIRA_PROJECT_KEY',  label: 'Chave do Projeto',        placeholder: 'SUP',
        hint: 'Sigla do projeto onde os chamados serão criados (ex: SUP, TI, PROJ).' },
    ],
  },
  {
    id: 'zabbix',
    icon: '📡',
    title: 'Zabbix',
    description: 'Monitoração de infraestrutura e disparo de alertas/chamadas em caso de incidentes (Módulo 3).',
    keys: [
      { key: 'ZABBIX_API_URL',              label: 'URL da API JSON-RPC',       placeholder: 'https://zabbix.empresa.com/api_jsonrpc.php' },
      { key: 'ZABBIX_USER',                 label: 'Usuário Zabbix',            placeholder: 'readonly_api_user' },
      { key: 'ZABBIX_PASSWORD',             label: 'Senha Zabbix',              type: 'password' },
      {
        key: 'ZABBIX_MIN_SEVERITY',
        label: 'Severidade Mínima para Alertas',
        type: 'select',
        options: [
          { value: '2', label: '2 — Warning' },
          { value: '3', label: '3 — Average' },
          { value: '4', label: '4 — High (Recomendado)' },
          { value: '5', label: '5 — Disaster' },
        ],
      },
      { key: 'ZABBIX_POLL_INTERVAL_MINUTES', label: 'Intervalo de Polling (minutos)', type: 'number',
        placeholder: '5', hint: 'Com que frequência verificar novos alertas no Zabbix.' },
    ],
  },
  {
    id: 'telegram',
    icon: '💬',
    title: 'Telegram Bot',
    description: 'Envio de relatórios diários de infraestrutura e alertas críticos via Telegram.',
    keys: [
      { key: 'TELEGRAM_BOT_TOKEN', label: 'Token do Bot',  type: 'password',
        hint: 'Obtenha criando um bot via @BotFather no Telegram.' },
      { key: 'TELEGRAM_CHAT_ID',   label: 'Chat ID',       placeholder: '-1001234567890',
        hint: 'ID do canal, grupo ou chat onde o bot enviará mensagens. Use @userinfobot para descobrir.' },
    ],
  },
  {
    id: 'infra',
    icon: '🏗️',
    title: 'Infraestrutura (Avançado)',
    description: 'Configurações internas de rede, banco de dados e WebRTC. Altere com cautela.',
    keys: [
      { key: 'ADMIN_USERNAME',          label: 'Username do Admin',          placeholder: 'admin' },
      { key: 'ADMIN_PASSWORD',          label: 'Senha do Admin',             type: 'password' },
      { key: 'POSTGRES_DB',             label: 'Nome do Banco de Dados',     placeholder: 'asteriskia' },
      { key: 'POSTGRES_USER',           label: 'Usuário PostgreSQL',         placeholder: 'asteriskia' },
      { key: 'POSTGRES_PASSWORD',       label: 'Senha PostgreSQL',           type: 'password' },
      { key: 'BACKEND_ALLOWED_ORIGINS', label: 'CORS — Origens Permitidas',
        placeholder: 'https://app.voiphash.com.br',
        hint: 'URLs do frontend que podem acessar a API. Separe por vírgula.' },
      { key: 'GRAFANA_ADMIN_PASSWORD',  label: 'Senha do Grafana',          type: 'password' },
      { key: 'VITE_API_URL',            label: 'URL da API (Frontend)',      placeholder: 'https://app.voiphash.com.br/api/v1' },
      { key: 'VITE_ASTERISK_WS',        label: 'WebSocket Asterisk (WebRTC)', placeholder: 'wss://app.voiphash.com.br/ws' },
    ],
  },
];

const MASK = '••••••••';

// ─── Componente Principal ─────────────────────────────────────────────────────

export default function Settings() {
  const [settings, setSettings]           = useState<Settings>({});
  const [edits, setEdits]                 = useState<Record<string, string>>({});
  const [loading, setLoading]             = useState(true);
  const [saving, setSaving]               = useState(false);
  const [applying, setApplying]           = useState(false);
  const [applyLog, setApplyLog]           = useState('');
  const [toast, setToast]                 = useState<{ type: 'success' | 'error'; msg: string } | null>(null);
  const [revealedKeys, setRevealedKeys]   = useState<Set<string>>(new Set());
  const [openSections, setOpenSections]   = useState<Set<string>>(new Set(SECTIONS.map(s => s.id)));
  const logRef = useRef<HTMLPreElement>(null);

  // ── Carrega configurações ──────────────────────────────────────────────────
  const load = async () => {
    setLoading(true);
    try {
      const res = await api.get<Settings>('/settings');
      setSettings(res.data);
      // Inicializa edits com os valores atuais
      const init: Record<string, string> = {};
      Object.entries(res.data).forEach(([k, v]) => { init[k] = v.value; });
      setEdits(init);
    } catch {
      showToast('error', 'Erro ao carregar configurações.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  // Scroll do log para o final
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [applyLog]);

  // ── Toast helper ───────────────────────────────────────────────────────────
  const showToast = (type: 'success' | 'error', msg: string) => {
    setToast({ type, msg });
    setTimeout(() => setToast(null), 4000);
  };

  // ── Campo edit ─────────────────────────────────────────────────────────────
  const handleChange = (key: string, value: string) => {
    setEdits(prev => ({ ...prev, [key]: value }));
  };

  // ── Salvar rascunho ────────────────────────────────────────────────────────
  const handleSave = async () => {
    setSaving(true);
    try {
      await api.post('/settings', edits);
      showToast('success', 'Configurações salvas! Clique em "Aplicar e Reiniciar" para colocar em produção.');
    } catch {
      showToast('error', 'Erro ao salvar configurações.');
    } finally {
      setSaving(false);
    }
  };

  // ── Aplicar (docker compose up) ────────────────────────────────────────────
  const handleApply = async () => {
    if (!confirm('Isso vai salvar as configurações e reiniciar todos os serviços do sistema.\n\nDeseja continuar?')) return;
    setApplying(true);
    setApplyLog('⏳ Salvando configurações...\n');
    try {
      // Salva primeiro
      await api.post('/settings', edits);
      setApplyLog(prev => prev + '✅ Configurações salvas.\n\n⏳ Reiniciando serviços (docker compose up -d)...\n\n');

      // Aplica
      const res = await api.post<{ status: string; log: string }>('/settings/apply');
      setApplyLog(prev => prev + res.data.log);

      if (res.data.status === 'success') {
        showToast('success', 'Serviços reiniciados com sucesso!');
      } else {
        showToast('error', 'Apply concluído com erros. Veja o log abaixo.');
      }
    } catch {
      setApplyLog(prev => prev + '\n❌ Erro ao comunicar com o servidor.');
      showToast('error', 'Erro ao aplicar configurações.');
    } finally {
      setApplying(false);
    }
  };

  // ── Toggle seção ──────────────────────────────────────────────────────────
  const toggleSection = (id: string) => {
    setOpenSections(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  // ── Toggle revelar senha ───────────────────────────────────────────────────
  const toggleReveal = (key: string) => {
    setRevealedKeys(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  };

  // ── Helpers de status ─────────────────────────────────────────────────────
  const sectionStatus = (section: Section): 'ok' | 'warn' | 'unknown' => {
    const defaultPlaceholders = ['changeme', 'your_', 'sua-', 'usuario_', 'senha_', MASK];
    const sectionKeys = section.keys.map(k => k.key);
    const anyDefault = sectionKeys.some(k => {
      const val = (edits[k] ?? '').toLowerCase();
      return val === '' || defaultPlaceholders.some(p => val.includes(p.toLowerCase()));
    });
    return anyDefault ? 'warn' : 'ok';
  };

  // ─── Render ────────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className="loading-state">
        <div className="spinner" />
        Carregando configurações…
      </div>
    );
  }

  return (
    <>
      {/* Toast */}
      {toast && (
        <div style={{
          position: 'fixed', top: 20, right: 24, zIndex: 9999,
          padding: '12px 20px', borderRadius: 10,
          background: toast.type === 'success' ? 'rgba(16,185,129,0.15)' : 'rgba(239,68,68,0.15)',
          border: `1px solid ${toast.type === 'success' ? '#10b981' : '#ef4444'}`,
          color: toast.type === 'success' ? '#6ee7b7' : '#fca5a5',
          backdropFilter: 'blur(12px)',
          fontSize: '0.875rem', maxWidth: 400,
          animation: 'fadeIn .2s ease',
          boxShadow: '0 8px 32px rgba(0,0,0,0.4)',
        }}>
          {toast.type === 'success' ? '✅' : '❌'} {toast.msg}
        </div>
      )}

      <div className="page-header">
        <h1>⚙️ Configurações do Sistema</h1>
        <p>Gerencie as integrações e variáveis de ambiente sem acessar o servidor. Após salvar, clique em <strong>"Aplicar e Reiniciar"</strong> para que as mudanças entrem em produção.</p>
      </div>

      <div className="page-body">

        {/* ── Barra de Ações ─────────────────────────────────────────────── */}
        <div style={{
          display: 'flex', gap: 12, marginBottom: 28, flexWrap: 'wrap', alignItems: 'center',
          padding: '16px 20px',
          background: 'rgba(255,255,255,0.03)',
          border: '1px solid rgba(255,255,255,0.08)',
          borderRadius: 12,
        }}>
          <div style={{ flex: 1, fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
            💡 <strong>Salvar Rascunho</strong> grava no arquivo <code style={codeStyle}>.env</code> sem reiniciar. <strong>Aplicar</strong> salva e executa <code style={codeStyle}>docker compose up -d</code>.
          </div>
          <button
            id="btn-save-settings"
            className="btn btn-ghost"
            onClick={handleSave}
            disabled={saving || applying}
            style={{ minWidth: 160 }}
          >
            {saving ? <><span className="spinner" style={{ width: 14, height: 14, margin: '0 6px 0 0' }} />Salvando…</> : '💾 Salvar Rascunho'}
          </button>
          <button
            id="btn-apply-settings"
            className="btn btn-primary"
            onClick={handleApply}
            disabled={saving || applying}
            style={{ minWidth: 200, background: 'linear-gradient(135deg, #7c3aed, #2563eb)' }}
          >
            {applying
              ? <><span className="spinner" style={{ width: 14, height: 14, margin: '0 6px 0 0', borderTopColor: '#fff' }} />Aplicando…</>
              : '▶ Aplicar e Reiniciar Serviços'}
          </button>
        </div>

        {/* ── Seções de Configuração ─────────────────────────────────────── */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {SECTIONS.map(section => {
            const open   = openSections.has(section.id);
            const status = sectionStatus(section);

            return (
              <div
                key={section.id}
                className="stat-card"
                style={{ padding: 0, overflow: 'hidden', transition: 'all .2s' }}
              >
                {/* Header da seção */}
                <button
                  id={`section-toggle-${section.id}`}
                  onClick={() => toggleSection(section.id)}
                  style={{
                    width: '100%', display: 'flex', alignItems: 'center',
                    gap: 12, padding: '16px 20px', background: 'none',
                    border: 'none', cursor: 'pointer', textAlign: 'left',
                    color: 'var(--text-primary)',
                  }}
                >
                  <span style={{ fontSize: '1.4rem' }}>{section.icon}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: '0.95rem' }}>{section.title}</div>
                    <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>
                      {section.description}
                    </div>
                  </div>
                  {/* Badge de status */}
                  <span style={{
                    fontSize: '0.72rem', fontWeight: 600, padding: '3px 10px', borderRadius: 20,
                    background: status === 'ok' ? 'rgba(16,185,129,0.15)' : 'rgba(245,158,11,0.15)',
                    color: status === 'ok' ? '#6ee7b7' : '#fcd34d',
                    border: `1px solid ${status === 'ok' ? 'rgba(16,185,129,0.3)' : 'rgba(245,158,11,0.3)'}`,
                  }}>
                    {status === 'ok' ? '✅ Configurado' : '⚠️ Pendente'}
                  </span>
                  <span style={{ color: 'var(--text-muted)', transition: 'transform .2s', display: 'inline-block',
                    transform: open ? 'rotate(180deg)' : 'rotate(0)' }}>▾</span>
                </button>

                {/* Campos da seção */}
                {open && (
                  <div style={{
                    padding: '0 20px 20px',
                    borderTop: '1px solid rgba(255,255,255,0.06)',
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))',
                    gap: '14px 24px',
                    marginTop: 16,
                  }}>
                    {section.keys.map(field => {
                      const meta     = settings[field.key];
                      const value    = edits[field.key] ?? '';
                      const isSecret = meta?.isSecret || field.type === 'password';
                      const revealed = revealedKeys.has(field.key);

                      return (
                        <div key={field.key} className="form-group" style={{ marginBottom: 0 }}>
                          <label className="form-label" htmlFor={`field-${field.key}`}>
                            {field.label}
                            {isSecret && (
                              <span style={{
                                marginLeft: 6, fontSize: '0.68rem', padding: '1px 6px',
                                borderRadius: 4, background: 'rgba(124,58,237,0.15)',
                                color: '#a78bfa',
                              }}>🔐 secreto</span>
                            )}
                          </label>

                          <div style={{ display: 'flex', gap: 6 }}>
                            {field.type === 'select' ? (
                              <select
                                id={`field-${field.key}`}
                                className="form-select"
                                value={value}
                                onChange={e => handleChange(field.key, e.target.value)}
                                style={{ flex: 1 }}
                              >
                                {field.options?.map(opt => (
                                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                                ))}
                              </select>
                            ) : (
                              <input
                                id={`field-${field.key}`}
                                type={isSecret && !revealed ? 'password' : field.type === 'number' ? 'number' : 'text'}
                                className="form-input"
                                style={{ flex: 1, fontFamily: isSecret ? 'monospace' : undefined }}
                                placeholder={field.placeholder ?? (isSecret ? '••••••••' : '')}
                                value={value}
                                onChange={e => handleChange(field.key, e.target.value)}
                              />
                            )}
                            {isSecret && (
                              <button
                                type="button"
                                className="btn btn-ghost btn-sm btn-icon"
                                title={revealed ? 'Ocultar' : 'Revelar valor'}
                                onClick={() => toggleReveal(field.key)}
                                style={{ flexShrink: 0 }}
                              >
                                {revealed ? '🙈' : '👁️'}
                              </button>
                            )}
                          </div>

                          {field.hint && (
                            <div style={{ fontSize: '0.73rem', color: 'var(--text-muted)', marginTop: 4, lineHeight: 1.4 }}>
                              {field.hint}
                            </div>
                          )}
                          {/* Exibe a chave técnica */}
                          <div style={{ fontSize: '0.68rem', color: 'rgba(148,163,184,0.45)', marginTop: 2, fontFamily: 'monospace' }}>
                            {field.key}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* ── Terminal de Log do Apply ───────────────────────────────────── */}
        {applyLog && (
          <div style={{ marginTop: 28 }}>
            <div style={{
              display: 'flex', alignItems: 'center', gap: 10,
              marginBottom: 10, fontSize: '0.85rem', fontWeight: 600,
              color: 'var(--text-secondary)',
            }}>
              <span>🖥️ Log de Execução</span>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => setApplyLog('')}
                style={{ marginLeft: 'auto', fontSize: '0.75rem' }}
              >
                Limpar
              </button>
            </div>
            <pre
              ref={logRef}
              style={{
                background: '#0d1117',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 10,
                padding: '16px 20px',
                fontFamily: '"JetBrains Mono", "Fira Code", monospace',
                fontSize: '0.8rem',
                lineHeight: 1.7,
                color: '#e6edf3',
                maxHeight: 380,
                overflowY: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
              }}
            >
              {applyLog}
              {applying && <span style={{ animation: 'blink 1s step-end infinite' }}>▌</span>}
            </pre>
          </div>
        )}

        {/* ── Botão Aplicar inferior (atalho) ───────────────────────────── */}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 24 }}>
          <button
            className="btn btn-ghost"
            onClick={handleSave}
            disabled={saving || applying}
          >
            {saving ? 'Salvando…' : '💾 Salvar Rascunho'}
          </button>
          <button
            className="btn btn-primary"
            onClick={handleApply}
            disabled={saving || applying}
            style={{ background: 'linear-gradient(135deg, #7c3aed, #2563eb)' }}
          >
            {applying ? 'Aplicando…' : '▶ Aplicar e Reiniciar Serviços'}
          </button>
        </div>

      </div>
    </>
  );
}

// ─── Estilos inline helpers ───────────────────────────────────────────────────
const codeStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.08)',
  padding: '1px 6px',
  borderRadius: 4,
  fontFamily: 'monospace',
  fontSize: '0.85em',
};
