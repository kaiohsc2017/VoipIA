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
  testable?: boolean;
  testKeys?: string[];
  requiredKeys?: string[];
  /** Containers Docker afetados ao salvar esta seção */
  affectedServices: string[];
}

interface FieldDef {
  key: string;
  label: string;
  placeholder?: string;
  hint?: string;
  type?: 'text' | 'password' | 'number' | 'select';
  options?: { value: string; label: string }[];
  validate?: 'url';
  required?: boolean;
}

interface HistoryEntry {
  id: number;
  changedAt: string;
  changedBy: string;
  envKey: string;
  oldValue: string | null;
  newValue: string | null;
  ipAddress: string | null;
}

type Tab = 'config' | 'history';

// ─── Seções — cada uma sabe quais serviços afeta ──────────────────────────────

// Seção SIP usa editor de bloco livre — não lista chaves individuais
interface SipRawSection extends Omit<Section, 'keys'> {
  rawSip: true;
  keys: FieldDef[];
}

const SIP_SECTION: SipRawSection = {
  id: 'sip',
  icon: '🔌',
  title: 'Tronco SIP (Operadora)',
  description: 'Cole o bloco [tronco-sip] completo. Ao salvar, ele substitui a seção no pjsip.conf.template e recarrega o PJSIP no Asterisk.',
  affectedServices: ['asterisk'],
  rawSip: true,
  keys: [],
};

const SECTIONS: Section[] = [
  {
    id: 'gemini',
    icon: '🤖',
    title: 'Google Gemini API',
    description: 'Chave e modelos usados pelo Agente de IA para transcrição (STT), raciocínio (LLM) e síntese de voz (TTS).',
    requiredKeys: ['GEMINI_API_KEY'],
    affectedServices: ['ai-agent'],
    keys: [
      { key: 'GEMINI_API_KEY',   label: 'API Key',               type: 'password', required: true,
        hint: 'Obtenha em https://aistudio.google.com/app/apikey' },
      { key: 'GEMINI_MODEL_STT', label: 'Modelo STT (Voz→Texto)',  placeholder: 'gemini-2.0-flash' },
      { key: 'GEMINI_MODEL_LLM', label: 'Modelo LLM (Raciocínio)', placeholder: 'gemini-2.0-flash' },
      { key: 'GEMINI_MODEL_TTS', label: 'Modelo TTS (Texto→Voz)',  placeholder: 'gemini-2.5-flash-preview-tts' },
    ],
  },
  {
    id: 'jira',
    icon: '🎫',
    title: 'Jira Cloud',
    description: 'Integração com o Jira Cloud para criação automática de chamados pela URA (Módulo 1).',
    testable: true,
    testKeys: ['JIRA_BASE_URL', 'JIRA_USER_EMAIL', 'JIRA_API_TOKEN'],
    requiredKeys: ['JIRA_BASE_URL', 'JIRA_USER_EMAIL', 'JIRA_API_TOKEN', 'JIRA_PROJECT_KEY'],
    affectedServices: ['backend'],
    keys: [
      { key: 'JIRA_BASE_URL',    label: 'URL da Instância Jira',  placeholder: 'https://empresa.atlassian.net',
        validate: 'url', required: true },
      { key: 'JIRA_USER_EMAIL',  label: 'E-mail do Usuário Jira', placeholder: 'usuario@empresa.com', required: true },
      { key: 'JIRA_API_TOKEN',   label: 'API Token Jira',         type: 'password', required: true,
        hint: 'Gere em https://id.atlassian.com/manage-profile/security/api-tokens' },
      { key: 'JIRA_PROJECT_KEY', label: 'Chave do Projeto',       placeholder: 'SUP', required: true,
        hint: 'Sigla do projeto onde os chamados serão criados (ex: SUP, TI, PROJ).' },
    ],
  },
  {
    id: 'zabbix',
    icon: '📡',
    title: 'Zabbix',
    description: 'Monitoração de infraestrutura e disparo de alertas/chamadas em caso de incidentes (Módulo 3).',
    testable: true,
    testKeys: ['ZABBIX_API_URL', 'ZABBIX_USER', 'ZABBIX_PASSWORD'],
    requiredKeys: ['ZABBIX_API_URL', 'ZABBIX_USER'],
    affectedServices: ['backend'],
    keys: [
      { key: 'ZABBIX_API_URL',               label: 'URL da API JSON-RPC',            placeholder: 'https://zabbix.empresa.com/api_jsonrpc.php',
        validate: 'url', required: true },
      { key: 'ZABBIX_USER',                  label: 'Usuário Zabbix',                 placeholder: 'readonly_api_user', required: true },
      { key: 'ZABBIX_PASSWORD',              label: 'Senha Zabbix',                   type: 'password' },
      { key: 'ZABBIX_MIN_SEVERITY',          label: 'Severidade Mínima para Alertas', type: 'select',
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
    testable: true,
    testKeys: ['TELEGRAM_BOT_TOKEN'],
    requiredKeys: ['TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID'],
    affectedServices: ['backend'],
    keys: [
      { key: 'TELEGRAM_BOT_TOKEN', label: 'Token do Bot', type: 'password', required: true,
        hint: 'Obtenha criando um bot via @BotFather no Telegram.' },
      { key: 'TELEGRAM_CHAT_ID',   label: 'Chat ID',      placeholder: '-1001234567890', required: true,
        hint: 'ID do canal, grupo ou chat onde o bot enviará mensagens. Use @userinfobot para descobrir.' },
    ],
  },
  {
    id: 'infra',
    icon: '🏗️',
    title: 'Infraestrutura (Avançado)',
    description: 'Configurações internas de rede, banco de dados e WebRTC. Altere com cautela.',
    affectedServices: ['backend', 'frontend'],
    keys: [
      { key: 'ADMIN_USERNAME',          label: 'Username do Admin',           placeholder: 'admin' },
      { key: 'ADMIN_PASSWORD',          label: 'Senha do Admin',              type: 'password' },
      { key: 'POSTGRES_DB',             label: 'Nome do Banco de Dados',      placeholder: 'asteriskia' },
      { key: 'POSTGRES_USER',           label: 'Usuário PostgreSQL',          placeholder: 'asteriskia' },
      { key: 'POSTGRES_PASSWORD',       label: 'Senha PostgreSQL',            type: 'password' },
      { key: 'BACKEND_ALLOWED_ORIGINS', label: 'CORS — Origens Permitidas',   placeholder: 'https://app.voiphash.com.br',
        hint: 'URLs do frontend que podem acessar a API. Separe por vírgula.' },
      { key: 'GRAFANA_ADMIN_PASSWORD',  label: 'Senha do Grafana',            type: 'password' },
      { key: 'VITE_API_URL',            label: 'URL da API (Frontend)',        placeholder: 'https://app.voiphash.com.br/api/v1' },
      { key: 'VITE_ASTERISK_WS',        label: 'WebSocket Asterisk (WebRTC)', placeholder: 'wss://app.voiphash.com.br/ws' },
    ],
  },
];

const MASK = '••••••••';

// ─── Helpers de validação ─────────────────────────────────────────────────────

function validateField(field: FieldDef, value: string): string | null {
  if (field.required && (!value || value === MASK || value.trim() === '')) {
    return 'Campo obrigatório';
  }
  if (field.validate === 'url' && value && value !== MASK) {
    try {
      const u = new URL(value);
      if (!['http:', 'https:'].includes(u.protocol)) return 'URL deve começar com http:// ou https://';
    } catch {
      return 'URL inválida (ex: https://empresa.atlassian.net)';
    }
  }
  return null;
}

function collectErrors(keys: FieldDef[], edits: Record<string, string>): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of keys) {
    const err = validateField(field, edits[field.key] ?? '');
    if (err) errors[field.key] = err;
  }
  return errors;
}

// ─── Componente Principal ─────────────────────────────────────────────────────

export default function Settings() {
  const [settings, setSettings]             = useState<Settings>({});
  const [edits, setEdits]                   = useState<Record<string, string>>({});
  const [savedSnapshot, setSavedSnapshot]   = useState<Record<string, string>>({});
  const [fieldErrors, setFieldErrors]       = useState<Record<string, string>>({});
  const [loading, setLoading]               = useState(true);
  const [savingSection, setSavingSection]   = useState<string | null>(null);
  const [applyingSection, setApplyingSection] = useState<string | null>(null);
  const [applyLog, setApplyLog]             = useState('');
  const [applyingSection_label, setApplyingLabel] = useState('');
  const [toast, setToast]                   = useState<{ type: 'success' | 'error'; msg: string } | null>(null);
  const [revealedKeys, setRevealedKeys]     = useState<Set<string>>(new Set());
  const [openSections, setOpenSections]     = useState<Set<string>>(new Set(['sip', ...SECTIONS.map(s => s.id)]));
  const [testingSection, setTestingSection] = useState<Record<string, 'idle' | 'loading' | 'ok' | 'error'>>({});
  const [testResults, setTestResults]       = useState<Record<string, string>>({});
  const [activeTab, setActiveTab]           = useState<Tab>('config');
  const [history, setHistory]               = useState<HistoryEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const logRef = useRef<HTMLPreElement>(null);

  // ── Estado do editor SIP raw ───────────────────────────────────────────────
  const [sipBlock, setSipBlock]             = useState('');
  const [sipOriginal, setSipOriginal]       = useState('');
  const [sipSaving, setSipSaving]           = useState(false);
  const [sipReloadStatus, setSipReloadStatus] = useState('');

  // ── Carrega configurações ──────────────────────────────────────────────────
  const load = async () => {
    setLoading(true);
    try {
      const [settingsRes, sipRes] = await Promise.all([
        api.get<Settings>('/settings'),
        api.get<{ block: string }>('/settings/sip-raw').catch(() => ({ data: { block: '' } })),
      ]);
      setSettings(settingsRes.data);
      const init: Record<string, string> = {};
      Object.entries(settingsRes.data).forEach(([k, v]) => { init[k] = v.value; });
      setEdits(init);
      setSavedSnapshot(init);
      setSipBlock(sipRes.data.block);
      setSipOriginal(sipRes.data.block);
    } catch {
      showToast('error', 'Erro ao carregar configurações.');
    } finally {
      setLoading(false);
    }
  };

  const loadHistory = async () => {
    setHistoryLoading(true);
    try {
      const res = await api.get<HistoryEntry[]>('/settings/history?limit=100');
      setHistory(res.data);
    } catch {
      showToast('error', 'Erro ao carregar histórico.');
    } finally {
      setHistoryLoading(false);
    }
  };

  useEffect(() => { load(); }, []);
  useEffect(() => { if (activeTab === 'history') loadHistory(); }, [activeTab]);
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [applyLog]);

  // ── Toast ──────────────────────────────────────────────────────────────────
  const showToast = (type: 'success' | 'error', msg: string) => {
    setToast({ type, msg });
    setTimeout(() => setToast(null), 5000);
  };

  // ── Campo edit ─────────────────────────────────────────────────────────────
  const handleChange = (key: string, value: string, field?: FieldDef) => {
    setEdits(prev => ({ ...prev, [key]: value }));
    if (field) {
      const err = validateField(field, value);
      setFieldErrors(prev => {
        const next = { ...prev };
        if (err) next[key] = err; else delete next[key];
        return next;
      });
    }
  };

  // ── Verifica se seção tem alterações pendentes ─────────────────────────────
  const sectionHasChanges = (section: Section): boolean =>
    section.keys.some(f => edits[f.key] !== savedSnapshot[f.key]);

  // ── Valida apenas uma seção ────────────────────────────────────────────────
  const validateSection = (section: Section): boolean => {
    const errors = collectErrors(section.keys, edits);
    setFieldErrors(prev => ({ ...prev, ...errors }));
    return Object.keys(errors).length === 0;
  };

  // ── Monta payload apenas com as chaves da seção ────────────────────────────
  const sectionPayload = (section: Section): Record<string, string> => {
    const payload: Record<string, string> = {};
    section.keys.forEach(f => { payload[f.key] = edits[f.key] ?? ''; });
    return payload;
  };

  // ── Salvar apenas a seção ──────────────────────────────────────────────────
  const handleSaveSection = async (section: Section) => {
    if (!validateSection(section)) {
      showToast('error', 'Corrija os campos destacados antes de salvar.');
      return;
    }
    setSavingSection(section.id);
    try {
      await api.post('/settings', sectionPayload(section));
      setSavedSnapshot(prev => ({ ...prev, ...sectionPayload(section) }));
      showToast('success', `${section.title} salvo! Clique em "Aplicar" para reiniciar somente os serviços afetados.`);
    } catch {
      showToast('error', 'Erro ao salvar. Tente novamente.');
    } finally {
      setSavingSection(null);
    }
  };

  // ── Aplicar apenas os serviços da seção ───────────────────────────────────
  const handleApplySection = async (section: Section) => {
    if (!validateSection(section)) {
      showToast('error', 'Corrija os campos antes de aplicar.');
      return;
    }
    const services = section.affectedServices.join(', ');
    if (!confirm(
      `Isso vai salvar "${section.title}" e reiniciar: ${services}.\n\nOs outros serviços não serão afetados. Continuar?`
    )) return;

    setApplyingSection(section.id);
    setApplyingLabel(section.title);
    setApplyLog(`⏳ Salvando ${section.title}…\n`);

    try {
      // 1. Salva no .env
      await api.post('/settings', sectionPayload(section));
      setSavedSnapshot(prev => ({ ...prev, ...sectionPayload(section) }));
      setApplyLog(prev => prev + `✅ Configurações salvas.\n\n⏳ Reiniciando: ${services}…\n\n`);

      // 2. Inicia apply passando os serviços específicos
      const startRes = await api.post<{ jobId: string }>('/settings/apply', {
        services: section.affectedServices,
      });
      const jobId = startRes.data.jobId;
      setApplyLog(prev => prev + `▶ Job: ${jobId}\n\n`);

      // 3. Polling
      let lastLen = 0;
      const poll = async (): Promise<void> => {
        const statusRes = await api.get<{ status: string; log: string }>(`/settings/apply/${jobId}`);
        const { status, log } = statusRes.data;
        if (log.length > lastLen) {
          setApplyLog(prev => prev + log.slice(lastLen));
          lastLen = log.length;
        }
        if (status === 'running') {
          await new Promise(r => setTimeout(r, 2000));
          return poll();
        }
        if (status === 'done') showToast('success', `${services} reiniciado(s) com sucesso!`);
        else showToast('error', 'Apply com erros — veja o log.');
      };
      await poll();
    } catch {
      setApplyLog(prev => prev + '\n❌ Erro ao comunicar com o servidor.');
      showToast('error', 'Erro ao aplicar.');
    } finally {
      setApplyingSection(null);
    }
  };

  // ── Salvar bloco SIP raw ───────────────────────────────────────────────────
  const handleSaveSipRaw = async () => {
    if (!sipBlock.trim()) { showToast('error', 'O bloco não pode ser vazio.'); return; }
    setSipSaving(true);
    setSipReloadStatus('');
    try {
      const res = await api.post<{ message: string; reloadStatus: string }>(
        '/settings/sip-raw', { block: sipBlock }
      );
      setSipOriginal(sipBlock);
      setSipReloadStatus(res.data.reloadStatus ?? '');
      const reloadOk = res.data.reloadStatus === 'ok';
      showToast('success',
        reloadOk
          ? 'Bloco SIP salvo e PJSIP recarregado com sucesso!'
          : `Bloco SIP salvo. Reload: ${res.data.reloadStatus} — verifique o Asterisk.`
      );
    } catch {
      showToast('error', 'Erro ao salvar bloco SIP.');
    } finally {
      setSipSaving(false);
    }
  };

  // ── Testar conexão ─────────────────────────────────────────────────────────
  const handleTest = async (section: Section) => {
    if (!section.testable) return;
    setTestingSection(prev => ({ ...prev, [section.id]: 'loading' }));
    setTestResults(prev => ({ ...prev, [section.id]: '' }));
    const body: Record<string, string> = {};
    (section.testKeys ?? []).forEach(key => { body[key] = edits[key] ?? ''; });
    try {
      const res = await api.post<{ success: boolean; message: string }>(`/settings/test/${section.id}`, body);
      setTestingSection(prev => ({ ...prev, [section.id]: res.data.success ? 'ok' : 'error' }));
      setTestResults(prev => ({ ...prev, [section.id]: res.data.message }));
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'Erro de comunicação.';
      setTestingSection(prev => ({ ...prev, [section.id]: 'error' }));
      setTestResults(prev => ({ ...prev, [section.id]: msg }));
    }
    setTimeout(() => setTestingSection(prev => ({ ...prev, [section.id]: 'idle' })), 8000);
  };

  // ── Toggle seção / revelar senha ──────────────────────────────────────────
  const toggleSection = (id: string) =>
    setOpenSections(prev => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const toggleReveal = (key: string) =>
    setRevealedKeys(prev => { const n = new Set(prev); n.has(key) ? n.delete(key) : n.add(key); return n; });

  // ── Status visual da seção ────────────────────────────────────────────────
  const sectionStatus = (section: Section): 'ok' | 'warn' | 'error' => {
    if (section.keys.some(k => !!fieldErrors[k.key])) return 'error';
    const bad = ['changeme', 'your_', 'sua-', 'usuario_', 'senha_', MASK];
    const anyDefault = section.keys.some(k => {
      const val = (edits[k.key] ?? '').toLowerCase();
      return val === '' || bad.some(p => val.includes(p.toLowerCase()));
    });
    return anyDefault ? 'warn' : 'ok';
  };

  // ─── Render ────────────────────────────────────────────────────────────────
  if (loading) return <div className="loading-state"><div className="spinner" />Carregando configurações…</div>;

  return (
    <>
      {toast && (
        <div style={{
          position: 'fixed', top: 20, right: 24, zIndex: 9999,
          padding: '12px 20px', borderRadius: 10,
          background: toast.type === 'success' ? 'rgba(16,185,129,0.15)' : 'rgba(239,68,68,0.15)',
          border: `1px solid ${toast.type === 'success' ? '#10b981' : '#ef4444'}`,
          color: toast.type === 'success' ? '#6ee7b7' : '#fca5a5',
          backdropFilter: 'blur(12px)', fontSize: '0.875rem', maxWidth: 400,
          boxShadow: '0 8px 32px rgba(0,0,0,0.4)',
        }}>
          {toast.type === 'success' ? '✅' : '❌'} {toast.msg}
        </div>
      )}

      <div className="page-header">
        <h1>⚙️ Configurações do Sistema</h1>
        <p>Salve e aplique cada integração de forma independente — apenas os serviços afetados são reiniciados.</p>
      </div>

      <div className="page-body">

        {/* Tabs */}
        <div style={{ display: 'flex', gap: 4, marginBottom: 24, borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
          {[{ id: 'config' as Tab, label: '⚙️ Configurações' }, { id: 'history' as Tab, label: '📋 Histórico' }].map(tab => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)} style={{
              padding: '10px 20px', border: 'none',
              borderBottom: activeTab === tab.id ? '2px solid #7c3aed' : '2px solid transparent',
              background: 'none', cursor: 'pointer',
              color: activeTab === tab.id ? '#a78bfa' : 'var(--text-muted)',
              fontWeight: activeTab === tab.id ? 600 : 400, fontSize: '0.875rem',
            }}>{tab.label}</button>
          ))}
        </div>

        {activeTab === 'config' && (
          <>
            {/* Hint global */}
            <div style={{
              display: 'flex', gap: 10, alignItems: 'flex-start',
              padding: '12px 16px', marginBottom: 24,
              background: 'rgba(124,58,237,0.08)', border: '1px solid rgba(124,58,237,0.2)',
              borderRadius: 10, fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.6,
            }}>
              <span style={{ fontSize: '1rem', flexShrink: 0 }}>💡</span>
              <span>
                Cada seção tem seus próprios botões <strong>Salvar</strong> e <strong>Aplicar</strong>.
                Salvar grava no <code style={codeStyle}>.env</code> sem reiniciar.
                Aplicar salva e reinicia <strong>somente os containers afetados</strong> por aquela seção — o restante do sistema continua sem impacto.
              </span>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

              {/* ── Painel SIP raw ─────────────────────────────────────────── */}
              {(() => {
                const sec = SIP_SECTION;
                const open = openSections.has(sec.id);
                const sipChanged = sipBlock !== sipOriginal;
                return (
                  <div key="sip" className="stat-card" style={{ padding: 0, overflow: 'hidden' }}>
                    <button
                      onClick={() => toggleSection(sec.id)}
                      style={{
                        width: '100%', display: 'flex', alignItems: 'center',
                        gap: 12, padding: '16px 20px', background: 'none',
                        border: 'none', cursor: 'pointer', textAlign: 'left', color: 'var(--text-primary)',
                      }}
                    >
                      <span style={{ fontSize: '1.4rem' }}>{sec.icon}</span>
                      <div style={{ flex: 1 }}>
                        <div style={{ fontWeight: 600, fontSize: '0.95rem', display: 'flex', alignItems: 'center', gap: 8 }}>
                          {sec.title}
                          {sipChanged && (
                            <span style={{
                              fontSize: '0.65rem', padding: '1px 7px', borderRadius: 20,
                              background: 'rgba(245,158,11,0.15)', color: '#fcd34d',
                              border: '1px solid rgba(245,158,11,0.3)',
                            }}>● alterado</span>
                          )}
                        </div>
                        <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>
                          {sec.description}
                        </div>
                      </div>
                      <span style={{
                        fontSize: '0.65rem', padding: '2px 6px', borderRadius: 6,
                        background: 'rgba(59,130,246,0.12)', color: '#93c5fd',
                        border: '1px solid rgba(59,130,246,0.2)',
                      }}>asterisk</span>
                      <span style={{ color: 'var(--text-muted)', transition: 'transform .2s', display: 'inline-block', transform: open ? 'rotate(180deg)' : 'rotate(0)' }}>▾</span>
                    </button>

                    {open && (
                      <div style={{ padding: '0 20px 20px', borderTop: '1px solid rgba(255,255,255,0.06)' }}>

                        {/* Dica de uso */}
                        <div style={{
                          marginTop: 14, padding: '10px 14px', borderRadius: 8,
                          background: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)',
                          fontSize: '0.78rem', color: '#93c5fd', lineHeight: 1.5,
                        }}>
                          💡 Cole aqui o bloco <code style={{ fontFamily: 'monospace', background: 'rgba(0,0,0,0.3)', padding: '1px 5px', borderRadius: 3 }}>[tronco-sip]</code> completo.
                          Ao salvar, o conteúdo substitui a seção no <code style={{ fontFamily: 'monospace', background: 'rgba(0,0,0,0.3)', padding: '1px 5px', borderRadius: 3 }}>pjsip.conf.template</code> e
                          o Asterisk executa <code style={{ fontFamily: 'monospace', background: 'rgba(0,0,0,0.3)', padding: '1px 5px', borderRadius: 3 }}>module reload res_pjsip</code> automaticamente.
                        </div>

                        {/* Textarea do bloco */}
                        <textarea
                          value={sipBlock}
                          onChange={e => setSipBlock(e.target.value)}
                          spellCheck={false}
                          rows={Math.max(10, sipBlock.split('\n').length + 2)}
                          style={{
                            display: 'block', width: '100%', marginTop: 14,
                            fontFamily: '"JetBrains Mono","Fira Code","Courier New",monospace',
                            fontSize: '0.82rem', lineHeight: 1.65,
                            background: '#0d1117', color: '#e6edf3',
                            border: '1px solid rgba(255,255,255,0.10)', borderRadius: 8,
                            padding: '14px 16px', resize: 'vertical', boxSizing: 'border-box',
                            outline: 'none', tabSize: 2,
                          }}
                          placeholder={`[tronco-sip]\ntype = endpoint\ntransport = transport-udp\ncontext = from-trunk\ndisallow = all\nallow = ulaw\nallow = alaw\nhost = 186.233.141.64\nqualify = yes`}
                        />

                        {/* Rodapé */}
                        <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', borderTop: '1px solid rgba(255,255,255,0.06)', paddingTop: 14 }}>
                          {sipReloadStatus && (
                            <span style={{
                              fontSize: '0.78rem',
                              color: sipReloadStatus === 'ok' ? '#6ee7b7' : '#fcd34d',
                            }}>
                              {sipReloadStatus === 'ok' ? '✅ PJSIP recarregado' : `⚠️ Reload: ${sipReloadStatus}`}
                            </span>
                          )}
                          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                            <button
                              className="btn btn-ghost btn-sm"
                              onClick={() => { setSipBlock(sipOriginal); setSipReloadStatus(''); }}
                              disabled={!sipChanged || sipSaving}
                              style={{ opacity: !sipChanged ? 0.4 : 1 }}
                            >
                              ↩ Descartar
                            </button>
                            <button
                              className="btn btn-primary btn-sm"
                              onClick={handleSaveSipRaw}
                              disabled={sipSaving || !sipBlock.trim()}
                              style={{ minWidth: 160 }}
                            >
                              {sipSaving
                                ? <><span className="spinner" style={{ width: 11, height: 11, margin: '0 5px 0 0', borderTopColor: '#fff' }} />Salvando…</>
                                : '💾 Salvar e Recarregar PJSIP'}
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })()}

              {SECTIONS.map(section => {
                const open       = openSections.has(section.id);
                const status     = sectionStatus(section);
                const hasChanges = sectionHasChanges(section);
                const isSaving   = savingSection === section.id;
                const isApplying = applyingSection === section.id;
                const testState  = testingSection[section.id] ?? 'idle';
                const testMsg    = testResults[section.id] ?? '';

                return (
                  <div key={section.id} className="stat-card" style={{ padding: 0, overflow: 'hidden' }}>

                    {/* Header da seção */}
                    <button
                      onClick={() => toggleSection(section.id)}
                      style={{
                        width: '100%', display: 'flex', alignItems: 'center',
                        gap: 12, padding: '16px 20px', background: 'none',
                        border: 'none', cursor: 'pointer', textAlign: 'left', color: 'var(--text-primary)',
                      }}
                    >
                      <span style={{ fontSize: '1.4rem' }}>{section.icon}</span>
                      <div style={{ flex: 1 }}>
                        <div style={{ fontWeight: 600, fontSize: '0.95rem', display: 'flex', alignItems: 'center', gap: 8 }}>
                          {section.title}
                          {hasChanges && (
                            <span style={{
                              fontSize: '0.65rem', padding: '1px 7px', borderRadius: 20,
                              background: 'rgba(245,158,11,0.15)', color: '#fcd34d',
                              border: '1px solid rgba(245,158,11,0.3)',
                            }}>● alterado</span>
                          )}
                        </div>
                        <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>
                          {section.description}
                        </div>
                      </div>
                      {/* Serviços afetados */}
                      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                        {section.affectedServices.map(s => (
                          <span key={s} style={{
                            fontSize: '0.65rem', padding: '2px 6px', borderRadius: 6,
                            background: 'rgba(59,130,246,0.12)', color: '#93c5fd',
                            border: '1px solid rgba(59,130,246,0.2)',
                          }}>{s}</span>
                        ))}
                      </div>
                      {/* Badge status */}
                      <span style={{
                        fontSize: '0.72rem', fontWeight: 600, padding: '3px 10px', borderRadius: 20, flexShrink: 0,
                        background: status === 'ok' ? 'rgba(16,185,129,0.15)' : status === 'error' ? 'rgba(239,68,68,0.15)' : 'rgba(245,158,11,0.15)',
                        color: status === 'ok' ? '#6ee7b7' : status === 'error' ? '#fca5a5' : '#fcd34d',
                        border: `1px solid ${status === 'ok' ? 'rgba(16,185,129,0.3)' : status === 'error' ? 'rgba(239,68,68,0.3)' : 'rgba(245,158,11,0.3)'}`,
                      }}>
                        {status === 'ok' ? '✅ Configurado' : status === 'error' ? '❌ Com erros' : '⚠️ Pendente'}
                      </span>
                      <span style={{ color: 'var(--text-muted)', transition: 'transform .2s', display: 'inline-block', transform: open ? 'rotate(180deg)' : 'rotate(0)' }}>▾</span>
                    </button>

                    {open && (
                      <div style={{ padding: '0 20px 20px', borderTop: '1px solid rgba(255,255,255,0.06)' }}>

                        {/* Campos */}
                        <div style={{
                          display: 'grid',
                          gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))',
                          gap: '14px 24px', marginTop: 16,
                        }}>
                          {section.keys.map(field => {
                            const meta     = settings[field.key];
                            const value    = edits[field.key] ?? '';
                            const isSecret = meta?.isSecret || field.type === 'password';
                            const revealed = revealedKeys.has(field.key);
                            const fieldErr = fieldErrors[field.key];

                            return (
                              <div key={field.key} className="form-group" style={{ marginBottom: 0 }}>
                                <label className="form-label" htmlFor={`field-${field.key}`}>
                                  {field.label}
                                  {field.required && <span style={{ color: '#ef4444', marginLeft: 3 }}>*</span>}
                                  {isSecret && (
                                    <span style={{ marginLeft: 6, fontSize: '0.68rem', padding: '1px 6px', borderRadius: 4, background: 'rgba(124,58,237,0.15)', color: '#a78bfa' }}>🔐 secreto</span>
                                  )}
                                </label>
                                <div style={{ display: 'flex', gap: 6 }}>
                                  {field.type === 'select' ? (
                                    <select id={`field-${field.key}`} className="form-select" value={value}
                                      onChange={e => handleChange(field.key, e.target.value, field)} style={{ flex: 1 }}>
                                      {field.options?.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                                    </select>
                                  ) : (
                                    <input
                                      id={`field-${field.key}`}
                                      type={isSecret && !revealed ? 'password' : field.type === 'number' ? 'number' : 'text'}
                                      className="form-input"
                                      style={{ flex: 1, fontFamily: isSecret ? 'monospace' : undefined,
                                        borderColor: fieldErr ? '#ef4444' : undefined,
                                        boxShadow: fieldErr ? '0 0 0 1px #ef4444' : undefined }}
                                      placeholder={field.placeholder ?? (isSecret ? '••••••••' : '')}
                                      value={value}
                                      onChange={e => handleChange(field.key, e.target.value, field)}
                                    />
                                  )}
                                  {isSecret && (
                                    <button type="button" className="btn btn-ghost btn-sm btn-icon"
                                      title={revealed ? 'Ocultar' : 'Revelar'} onClick={() => toggleReveal(field.key)}>
                                      {revealed ? '🙈' : '👁️'}
                                    </button>
                                  )}
                                </div>
                                {fieldErr && <div style={{ color: '#f87171', fontSize: '0.73rem', marginTop: 4 }}>⚠ {fieldErr}</div>}
                                {field.hint && !fieldErr && <div style={{ fontSize: '0.73rem', color: 'var(--text-muted)', marginTop: 4, lineHeight: 1.4 }}>{field.hint}</div>}
                                <div style={{ fontSize: '0.68rem', color: 'rgba(148,163,184,0.45)', marginTop: 2, fontFamily: 'monospace' }}>{field.key}</div>
                              </div>
                            );
                          })}
                        </div>

                        {/* Rodapé da seção: testar + salvar + aplicar */}
                        <div style={{ marginTop: 20, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', borderTop: '1px solid rgba(255,255,255,0.06)', paddingTop: 16 }}>

                          {/* Testar conexão */}
                          {section.testable && (
                            <button className="btn btn-ghost btn-sm" onClick={() => handleTest(section)}
                              disabled={testState === 'loading'}
                              style={{
                                borderColor: testState === 'ok' ? '#10b981' : testState === 'error' ? '#ef4444' : undefined,
                                color: testState === 'ok' ? '#6ee7b7' : testState === 'error' ? '#fca5a5' : undefined,
                              }}>
                              {testState === 'loading' ? <><span className="spinner" style={{ width: 12, height: 12, margin: '0 6px 0 0' }} />Testando…</> :
                               testState === 'ok' ? '✅ Conexão OK' : testState === 'error' ? '❌ Falhou' : '🔗 Testar'}
                            </button>
                          )}
                          {testMsg && (
                            <span style={{ fontSize: '0.78rem', color: testState === 'ok' ? '#6ee7b7' : '#fca5a5', flex: 1 }}>{testMsg}</span>
                          )}

                          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
                            {/* Indicador de serviços */}
                            <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
                              Afeta: <strong style={{ color: '#93c5fd' }}>{section.affectedServices.join(', ')}</strong>
                            </span>

                            {/* Salvar */}
                            <button className="btn btn-ghost btn-sm" onClick={() => handleSaveSection(section)}
                              disabled={isSaving || isApplying || !hasChanges}
                              style={{ minWidth: 110, opacity: !hasChanges ? 0.45 : 1 }}>
                              {isSaving ? <><span className="spinner" style={{ width: 11, height: 11, margin: '0 5px 0 0' }} />Salvando…</> : '💾 Salvar'}
                            </button>

                            {/* Aplicar */}
                            <button className="btn btn-primary btn-sm" onClick={() => handleApplySection(section)}
                              disabled={isSaving || isApplying}
                              style={{ minWidth: 130, background: isApplying ? undefined : 'linear-gradient(135deg,#7c3aed,#2563eb)' }}>
                              {isApplying
                                ? <><span className="spinner" style={{ width: 11, height: 11, margin: '0 5px 0 0', borderTopColor: '#fff' }} />Aplicando…</>
                                : '▶ Salvar e Aplicar'}
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>

            {/* Log do apply */}
            {applyLog && (
              <div style={{ marginTop: 28 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-secondary)' }}>
                  <span>🖥️ Log — {applyingSection_label || 'Apply'}</span>
                  <button className="btn btn-ghost btn-sm" onClick={() => setApplyLog('')} style={{ marginLeft: 'auto', fontSize: '0.75rem' }}>Limpar</button>
                </div>
                <pre ref={logRef} style={{
                  background: '#0d1117', border: '1px solid rgba(255,255,255,0.08)',
                  borderRadius: 10, padding: '16px 20px',
                  fontFamily: '"JetBrains Mono","Fira Code",monospace',
                  fontSize: '0.8rem', lineHeight: 1.7, color: '#e6edf3',
                  maxHeight: 320, overflowY: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                }}>
                  {applyLog}
                  {applyingSection && <span style={{ animation: 'blink 1s step-end infinite' }}>▌</span>}
                </pre>
              </div>
            )}
          </>
        )}

        {activeTab === 'history' && (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20 }}>
              <div style={{ flex: 1, fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                Registro de todas as alterações realizadas no <code style={codeStyle}>.env</code> via interface web.
              </div>
              <button className="btn btn-ghost btn-sm" onClick={loadHistory} disabled={historyLoading}>
                {historyLoading ? '…' : '🔄 Atualizar'}
              </button>
            </div>
            {historyLoading ? (
              <div className="loading-state"><div className="spinner" />Carregando histórico…</div>
            ) : history.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '60px 20px', color: 'var(--text-muted)' }}>
                <div style={{ fontSize: '2.5rem', marginBottom: 12 }}>📋</div>
                <div>Nenhuma alteração registrada ainda.</div>
              </div>
            ) : (
              <div className="table-container">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Data / Hora</th><th>Usuário</th><th>Variável</th><th>Valor Anterior</th><th>Novo Valor</th><th>IP</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.map(entry => (
                      <tr key={entry.id}>
                        <td style={{ whiteSpace: 'nowrap', fontFamily: 'monospace', fontSize: '0.8rem' }}>
                          {new Date(entry.changedAt).toLocaleString('pt-BR')}
                        </td>
                        <td><span style={{ background: 'rgba(124,58,237,0.15)', color: '#a78bfa', padding: '2px 8px', borderRadius: 6, fontSize: '0.78rem' }}>{entry.changedBy}</span></td>
                        <td style={{ fontFamily: 'monospace', fontSize: '0.82rem', color: '#93c5fd' }}>{entry.envKey}</td>
                        <td style={{ fontFamily: 'monospace', fontSize: '0.8rem', color: 'var(--text-muted)', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>{entry.oldValue ?? <em style={{ opacity: 0.5 }}>—</em>}</td>
                        <td style={{ fontFamily: 'monospace', fontSize: '0.8rem', color: '#6ee7b7', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>{entry.newValue ?? <em style={{ opacity: 0.5 }}>—</em>}</td>
                        <td style={{ fontFamily: 'monospace', fontSize: '0.78rem', color: 'var(--text-muted)' }}>{entry.ipAddress ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>
    </>
  );
}

const codeStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.08)', padding: '1px 6px',
  borderRadius: 4, fontFamily: 'monospace', fontSize: '0.85em',
};
