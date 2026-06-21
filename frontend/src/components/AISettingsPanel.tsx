import React, { useEffect, useState, useCallback } from 'react';
import api from '../api/client';

// ─── Tipos ────────────────────────────────────────────────────────────────────

interface ProviderDef {
  id: string;
  name: string;
  capabilities: string[];
  hasKey: boolean;
}

interface ModelInfo {
  id: string;
  displayName: string;
  description: string;
  tags: string[];      // speed | deep | voice | cost | priv
  capabilities: string[];
}

interface ChainEntry {
  id?: number;
  capability: string;
  priority: number;
  provider: string;
  modelId: string;
  isEnabled: boolean;
}

type Capability = 'STT' | 'LLM' | 'TTS';

// ─── Constantes ───────────────────────────────────────────────────────────────

const CAPABILITIES: { id: Capability; icon: string; label: string; desc: string }[] = [
  { id: 'STT', icon: '🎙️', label: 'STT — Transcrição de voz',  desc: 'Converte áudio do chamador em texto' },
  { id: 'LLM', icon: '🧠', label: 'LLM — Raciocínio e resposta', desc: 'Processa o texto e gera a resposta' },
  { id: 'TTS', icon: '🔊', label: 'TTS — Síntese de voz',       desc: 'Converte a resposta em áudio' },
];

const TAG_LABELS: Record<string, string> = {
  speed: '⚡ Rápido',
  deep:  '🔍 Raciocínio profundo',
  voice: '🎤 Voz expressiva',
  cost:  '💰 Econômico',
  priv:  '🔒 Privado/local',
};

// ─── Estilos inline compartilhados ───────────────────────────────────────────

const card = {
  border: '0.5px solid var(--color-border-tertiary)',
  borderRadius: 'var(--border-radius-lg)',
  overflow: 'hidden',
  marginBottom: '12px',
} as React.CSSProperties;

const capHead = {
  display: 'flex', alignItems: 'center', gap: '10px',
  padding: '11px 14px',
  background: 'var(--color-background-secondary)',
  borderBottom: '0.5px solid var(--color-border-tertiary)',
} as React.CSSProperties;

const chainItemBase = {
  display: 'flex', alignItems: 'center', gap: '8px',
  padding: '9px 11px',
  background: 'var(--color-background-primary)',
  border: '0.5px solid var(--color-border-tertiary)',
  borderRadius: 'var(--border-radius-md)',
  marginBottom: '6px',
} as React.CSSProperties;

const chainItemPrimary = {
  ...chainItemBase,
  borderColor: 'var(--color-border-info)',
  background: 'var(--color-background-info)',
} as React.CSSProperties;

// ─── Componente Principal ─────────────────────────────────────────────────────

interface AISettingsPanelProps {
  open: boolean;
  onToggle: () => void;
}

export const AISettingsPanel: React.FC<AISettingsPanelProps> = ({ open, onToggle }) => {
  const [providers, setProviders]           = useState<ProviderDef[]>([]);
  const [chains, setChains]                 = useState<Record<Capability, ChainEntry[]>>({ STT: [], LLM: [], TTS: [] });
  const [saving, setSaving]                 = useState(false);
  const [toast, setToast]                   = useState<string | null>(null);

  // Modal
  const [modalOpen, setModalOpen]           = useState(false);
  const [modalCap, setModalCap]             = useState<Capability>('LLM');
  const [selProvider, setSelProvider]       = useState<ProviderDef | null>(null);
  const [models, setModels]                 = useState<ModelInfo[]>([]);
  const [modelsLoading, setModelsLoading]   = useState(false);
  const [selModel, setSelModel]             = useState<ModelInfo | null>(null);

  // Key editing
  const [editingKey, setEditingKey]         = useState<string | null>(null);
  const [keyInput, setKeyInput]             = useState('');
  const [savingKey, setSavingKey]           = useState(false);

  // ── Carregar dados ──────────────────────────────────────────────────────────

  const loadAll = useCallback(async () => {
    try {
      const [provRes, chainRes] = await Promise.all([
        api.get<ProviderDef[]>('/ai/providers'),
        api.get<ChainEntry[]>('/ai/chain'),
      ]);
      setProviders(provRes.data);
      const grouped: Record<Capability, ChainEntry[]> = { STT: [], LLM: [], TTS: [] };
      chainRes.data.forEach(e => {
        const cap = e.capability as Capability;
        if (grouped[cap]) grouped[cap].push(e);
      });
      setChains(grouped);
    } catch (err) {
      console.error('Erro ao carregar configuração de IA', err);
    }
  }, []);

  useEffect(() => { if (open) loadAll(); }, [open, loadAll]);

  // ── Salvar chains ───────────────────────────────────────────────────────────

  const saveChains = async () => {
    setSaving(true);
    try {
      await Promise.all(
        (['STT','LLM','TTS'] as Capability[]).map(cap =>
          api.put(`/ai/chain/${cap}`, chains[cap].map(e => ({
            provider: e.provider,
            modelId:  e.modelId,
          })))
        )
      );
      showToast('Chains salvas · ai-agent aplica na próxima chamada');
    } catch {
      showToast('Erro ao salvar chains');
    } finally {
      setSaving(false);
    }
  };

  // ── Salvar key ──────────────────────────────────────────────────────────────

  const saveKey = async (providerId: string) => {
    if (!keyInput.trim()) return;
    setSavingKey(true);
    try {
      await api.put(`/ai/providers/${providerId}/key`, { apiKey: keyInput.trim() });
      setEditingKey(null);
      setKeyInput('');
      await loadAll();
      showToast('Key salva · modelos disponíveis para ' + providers.find(p => p.id === providerId)?.name);
    } catch {
      showToast('Erro ao salvar key');
    } finally {
      setSavingKey(false);
    }
  };

  // ── Modal ───────────────────────────────────────────────────────────────────

  const openModal = (cap: Capability) => {
    setModalCap(cap);
    setSelProvider(null);
    setSelModel(null);
    setModels([]);
    setModalOpen(true);
  };

  const selectProvider = async (prov: ProviderDef) => {
    setSelProvider(prov);
    setSelModel(null);
    setModels([]);
    setModelsLoading(true);
    try {
      const res = await api.get<ModelInfo[]>(`/ai/providers/${prov.id}/models?cap=${modalCap}`);
      setModels(res.data);
      if (res.data.length > 0) setSelModel(res.data[0]);
    } catch {
      setModels([]);
    } finally {
      setModelsLoading(false);
    }
  };

  const confirmAdd = () => {
    if (!selProvider || !selModel) return;
    const cap = modalCap;
    const current = chains[cap];
    const newEntry: ChainEntry = {
      capability: cap,
      priority: current.length + 1,
      provider: selProvider.id,
      modelId: selModel.id,
      isEnabled: true,
    };
    setChains(prev => ({ ...prev, [cap]: [...prev[cap], newEntry] }));
    setModalOpen(false);
  };

  const removeEntry = (cap: Capability, idx: number) => {
    setChains(prev => ({
      ...prev,
      [cap]: prev[cap]
        .filter((_, i) => i !== idx)
        .map((e, i) => ({ ...e, priority: i + 1 })),
    }));
  };

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  };

  // ─── Render ──────────────────────────────────────────────────────────────────

  const providerName = (id: string) => providers.find(p => p.id === id)?.name ?? id;

  return (
    <>
      {/* Header accordion (igual às demais seções do Settings) */}
      <div style={card}>
        <button
          onClick={onToggle}
          style={{
            width: '100%', display: 'flex', alignItems: 'center',
            padding: '14px 16px', background: 'var(--color-background-secondary)',
            border: 'none', borderBottom: open ? '0.5px solid var(--color-border-tertiary)' : 'none',
            cursor: 'pointer', color: 'var(--color-text-primary)',
          }}
        >
          <span style={{ fontSize: '18px', marginRight: '10px' }}>🤖</span>
          <div style={{ textAlign: 'left', flex: 1 }}>
            <div style={{ fontWeight: 500, fontSize: '14px' }}>Inteligência Artificial</div>
            <div style={{ fontSize: '12px', color: 'var(--color-text-secondary)', marginTop: '2px' }}>
              Provedores e modelos para STT, LLM e TTS — com fallback automático
            </div>
          </div>
          <span style={{ color: 'var(--color-text-secondary)', fontSize: '18px' }}>{open ? '▲' : '▼'}</span>
        </button>

        {open && (
          <div style={{ padding: '16px' }}>

            {/* ── Capability Chains ── */}
            {CAPABILITIES.map(cap => {
              const entries = chains[cap.id] ?? [];
              return (
                <div key={cap.id} style={{ ...card, marginBottom: '10px' }}>
                  <div style={capHead}>
                    <span style={{ fontSize: '16px' }}>{cap.icon}</span>
                    <span style={{ fontWeight: 500, fontSize: '13px' }}>{cap.label}</span>
                    <span style={{ fontSize: '11px', color: 'var(--color-text-secondary)', marginLeft: 'auto' }}>
                      ordem = prioridade de fallback
                    </span>
                  </div>
                  <div style={{ padding: '12px 14px' }}>
                    {entries.map((entry, idx) => (
                      <div key={idx} style={idx === 0 ? chainItemPrimary : chainItemBase}>
                        <span style={{ opacity: .4, fontSize: '13px' }}>⠿</span>
                        <span style={{
                          fontSize: '11px', fontWeight: 500, padding: '2px 7px',
                          borderRadius: '20px', background: 'var(--color-background-tertiary)',
                          color: 'var(--color-text-secondary)', minWidth: '20px', textAlign: 'center',
                        }}>{idx + 1}</span>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ fontSize: '13px', fontWeight: 500 }}>{providerName(entry.provider)}</div>
                          <div style={{ fontSize: '11px', color: 'var(--color-text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {entry.modelId}
                          </div>
                        </div>
                        <span style={{
                          fontSize: '10px', padding: '2px 7px', borderRadius: '20px', fontWeight: 500,
                          background: idx === 0 ? 'var(--color-background-info)' : 'var(--color-background-secondary)',
                          color: idx === 0 ? 'var(--color-text-info)' : 'var(--color-text-secondary)',
                        }}>{idx === 0 ? 'primário' : 'fallback'}</span>
                        <button
                          onClick={() => removeEntry(cap.id, idx)}
                          style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--color-text-secondary)', fontSize: '15px', opacity: .5, padding: '0 2px' }}
                        >×</button>
                      </div>
                    ))}
                    <button
                      onClick={() => openModal(cap.id)}
                      style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px',
                        width: '100%', padding: '8px',
                        border: '0.5px dashed var(--color-border-secondary)',
                        borderRadius: 'var(--border-radius-md)', background: 'none',
                        cursor: 'pointer', fontSize: '12px', color: 'var(--color-text-secondary)',
                      }}
                    >+ Adicionar provedor de fallback</button>
                  </div>
                </div>
              );
            })}

            {/* ── Salvar chains ── */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginBottom: '20px' }}>
              <button
                onClick={saveChains}
                disabled={saving}
                style={{
                  padding: '8px 18px', fontSize: '13px', cursor: 'pointer',
                  background: 'var(--color-text-primary)', color: 'var(--color-background-primary)',
                  border: 'none', borderRadius: 'var(--border-radius-md)', opacity: saving ? .6 : 1,
                }}
              >{saving ? 'Salvando…' : 'Salvar chains'}</button>
            </div>

            {/* ── API Keys ── */}
            <div style={{ ...card, marginBottom: 0 }}>
              <div style={capHead}>
                <span style={{ fontSize: '16px' }}>🔑</span>
                <span style={{ fontWeight: 500, fontSize: '13px' }}>API Keys dos provedores</span>
                <span style={{ fontSize: '11px', color: 'var(--color-text-secondary)', marginLeft: 'auto' }}>necessária para buscar modelos</span>
              </div>
              <div style={{ padding: '12px 14px' }}>
                {providers.map(prov => (
                  <div key={prov.id} style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '9px 0', borderBottom: '0.5px solid var(--color-border-tertiary)' }}>
                    <span style={{ fontWeight: 500, fontSize: '13px', width: '120px', flexShrink: 0 }}>{prov.name}</span>
                    {prov.id === 'local' ? (
                      <span style={{ fontSize: '12px', color: 'var(--color-text-success)' }}>✓ Roda localmente — sem API key</span>
                    ) : editingKey === prov.id ? (
                      <>
                        <input
                          type="password"
                          value={keyInput}
                          onChange={e => setKeyInput(e.target.value)}
                          placeholder="Informe a API key…"
                          style={{
                            flex: 1, padding: '5px 9px', fontSize: '12px',
                            border: '0.5px solid var(--color-border-info)',
                            borderRadius: 'var(--border-radius-md)',
                            background: 'var(--color-background-primary)',
                            color: 'var(--color-text-primary)', fontFamily: 'var(--font-mono)',
                          }}
                        />
                        <button onClick={() => saveKey(prov.id)} disabled={savingKey} style={{ padding: '5px 12px', fontSize: '12px', cursor: 'pointer', background: 'var(--color-text-primary)', color: 'var(--color-background-primary)', border: 'none', borderRadius: 'var(--border-radius-md)' }}>
                          {savingKey ? '…' : 'Salvar'}
                        </button>
                        <button onClick={() => { setEditingKey(null); setKeyInput(''); }} style={{ padding: '5px 10px', fontSize: '12px', cursor: 'pointer', border: '0.5px solid var(--color-border-secondary)', borderRadius: 'var(--border-radius-md)', background: 'none', color: 'var(--color-text-secondary)' }}>
                          Cancelar
                        </button>
                      </>
                    ) : (
                      <>
                        <span style={{ flex: 1, fontSize: '12px', color: prov.hasKey ? 'var(--color-text-success)' : 'var(--color-text-secondary)' }}>
                          {prov.hasKey ? '✓ Key configurada' : 'Sem key — modelos indisponíveis'}
                        </span>
                        <button onClick={() => { setEditingKey(prov.id); setKeyInput(''); }} style={{ padding: '5px 10px', fontSize: '11px', cursor: 'pointer', border: '0.5px solid var(--color-border-secondary)', borderRadius: 'var(--border-radius-md)', background: 'none', color: 'var(--color-text-secondary)' }}>
                          Editar
                        </button>
                      </>
                    )}
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* ── Modal: Adicionar provedor ── */}
      {modalOpen && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', zIndex: 100, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--color-background-primary)', border: '0.5px solid var(--color-border-tertiary)', borderRadius: 'var(--border-radius-lg)', width: '100%', maxWidth: '480px', overflow: 'hidden' }}>
            <div style={{ padding: '14px 16px', borderBottom: '0.5px solid var(--color-border-tertiary)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span style={{ fontWeight: 500, fontSize: '14px' }}>Adicionar provedor — {modalCap}</span>
              <button onClick={() => setModalOpen(false)} style={{ background: 'none', border: 'none', fontSize: '18px', cursor: 'pointer', color: 'var(--color-text-secondary)' }}>×</button>
            </div>
            <div style={{ padding: '14px 16px' }}>

              {/* Passo 1: escolher provedor */}
              <div style={{ fontSize: '11px', fontWeight: 500, color: 'var(--color-text-secondary)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: '8px' }}>
                1. Escolha o provedor
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '7px', marginBottom: '14px' }}>
                {providers.filter(p => p.capabilities.includes(modalCap)).map(prov => {
                  const disabled = !prov.hasKey && prov.id !== 'local';
                  return (
                    <div
                      key={prov.id}
                      onClick={() => !disabled && selectProvider(prov)}
                      style={{
                        border: `0.5px solid ${selProvider?.id === prov.id ? 'var(--color-border-info)' : 'var(--color-border-tertiary)'}`,
                        background: selProvider?.id === prov.id ? 'var(--color-background-info)' : 'var(--color-background-primary)',
                        borderRadius: 'var(--border-radius-md)', padding: '9px 11px',
                        cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? .45 : 1,
                      }}
                    >
                      <div style={{ fontWeight: 500, fontSize: '13px' }}>{prov.name}</div>
                      <div style={{ fontSize: '11px', color: 'var(--color-text-secondary)', marginTop: '2px' }}>{prov.capabilities.join(' · ')}</div>
                      <div style={{ fontSize: '10px', marginTop: '3px', color: prov.hasKey || prov.id === 'local' ? 'var(--color-text-success)' : 'var(--color-text-secondary)' }}>
                        {prov.id === 'local' ? '✓ local' : prov.hasKey ? '✓ key configurada' : '⚠ sem key'}
                      </div>
                    </div>
                  );
                })}
              </div>

              {/* Passo 2: escolher modelo */}
              {(selProvider || modelsLoading) && (
                <>
                  <div style={{ fontSize: '11px', fontWeight: 500, color: 'var(--color-text-secondary)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: '8px' }}>
                    2. Selecione o modelo
                  </div>
                  <div style={{ border: '0.5px solid var(--color-border-tertiary)', borderRadius: 'var(--border-radius-md)', overflow: 'hidden', maxHeight: '200px', overflowY: 'auto', marginBottom: '14px' }}>
                    {modelsLoading ? (
                      <div style={{ padding: '12px', fontSize: '12px', color: 'var(--color-text-secondary)', textAlign: 'center' }}>
                        Buscando modelos na API de {selProvider?.name}…
                      </div>
                    ) : models.length === 0 ? (
                      <div style={{ padding: '12px', fontSize: '12px', color: 'var(--color-text-secondary)', textAlign: 'center' }}>
                        Nenhum modelo disponível para {modalCap}
                      </div>
                    ) : models.map(model => (
                      <div
                        key={model.id}
                        onClick={() => setSelModel(model)}
                        style={{
                          padding: '9px 12px', cursor: 'pointer',
                          borderBottom: '0.5px solid var(--color-border-tertiary)',
                          background: selModel?.id === model.id ? 'var(--color-background-info)' : 'transparent',
                        }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                          <span style={{ fontSize: '13px', fontWeight: 500 }}>{model.displayName || model.id}</span>
                          <span style={{ display: 'flex', gap: '4px' }}>
                            {model.tags.map(tag => (
                              <span key={tag} style={{
                                fontSize: '10px', padding: '2px 6px', borderRadius: '20px',
                                background: 'var(--color-background-secondary)',
                                color: 'var(--color-text-secondary)',
                              }}>{TAG_LABELS[tag] ?? tag}</span>
                            ))}
                          </span>
                        </div>
                        {model.description && (
                          <div style={{ fontSize: '11px', color: 'var(--color-text-secondary)', marginTop: '3px' }}>
                            {model.description}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </>
              )}
            </div>

            <div style={{ padding: '12px 16px', borderTop: '0.5px solid var(--color-border-tertiary)', display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
              <button onClick={() => setModalOpen(false)} style={{ padding: '7px 14px', fontSize: '13px', cursor: 'pointer', border: '0.5px solid var(--color-border-secondary)', borderRadius: 'var(--border-radius-md)', background: 'none', color: 'var(--color-text-primary)' }}>
                Cancelar
              </button>
              <button
                onClick={confirmAdd}
                disabled={!selProvider || !selModel}
                style={{
                  padding: '7px 14px', fontSize: '13px', cursor: 'pointer',
                  background: 'var(--color-text-primary)', color: 'var(--color-background-primary)',
                  border: 'none', borderRadius: 'var(--border-radius-md)',
                  opacity: (!selProvider || !selModel) ? .4 : 1,
                }}
              >Adicionar</button>
            </div>
          </div>
        </div>
      )}

      {/* Toast */}
      {toast && (
        <div style={{
          position: 'fixed', bottom: '1.25rem', left: '50%', transform: 'translateX(-50%)',
          background: 'var(--color-text-primary)', color: 'var(--color-background-primary)',
          padding: '8px 18px', borderRadius: '20px', fontSize: '12px', zIndex: 200,
        }}>{toast}</div>
      )}
    </>
  );
};
