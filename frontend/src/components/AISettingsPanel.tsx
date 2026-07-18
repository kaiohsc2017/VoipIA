import React, { useEffect, useState, useCallback } from 'react';
import api from '../api/client';
import type { AiModelPricing, PricingFetchResult } from '../api/types';

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
  tags: string[];
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

const CAPABILITIES: { id: Capability; icon: string; label: string; desc: string }[] = [
  { id: 'STT', icon: '🎙️', label: 'STT — Transcrição de voz',    desc: 'Converte áudio do chamador em texto' },
  { id: 'LLM', icon: '🧠', label: 'LLM — Raciocínio e resposta', desc: 'Processa o texto e gera a resposta' },
  { id: 'TTS', icon: '🔊', label: 'TTS — Síntese de voz',         desc: 'Converte a resposta em áudio para o chamador' },
];

const TAG_LABELS: Record<string, { label: string; cls: string }> = {
  speed: { label: '⚡ Rápido',             cls: 'badge-success' },
  deep:  { label: '🔍 Raciocínio profundo', cls: 'badge-info'    },
  voice: { label: '🎤 Voz expressiva',      cls: 'badge-accent'  },
  cost:  { label: '💰 Econômico',           cls: 'badge-warning' },
  priv:  { label: '🔒 Privado/local',       cls: 'badge-gray'    },
};

// ─── Componente ───────────────────────────────────────────────────────────────

interface AISettingsPanelProps {
  open: boolean;
  onToggle: () => void;
}

export const AISettingsPanel: React.FC<AISettingsPanelProps> = ({ open, onToggle }) => {
  const [providers, setProviders] = useState<ProviderDef[]>([]);
  const [chains, setChains]       = useState<Record<Capability, ChainEntry[]>>({ STT: [], LLM: [], TTS: [] });
  const [saving, setSaving]       = useState(false);
  const [toast, setToast]         = useState<{ msg: string; type: 'success' | 'error' } | null>(null);

  // Modal
  const [modalOpen, setModalOpen]         = useState(false);
  const [modalCap, setModalCap]           = useState<Capability>('LLM');
  const [selProvider, setSelProvider]     = useState<ProviderDef | null>(null);
  const [models, setModels]               = useState<ModelInfo[]>([]);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [selModel, setSelModel]           = useState<ModelInfo | null>(null);

  // Key editing
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [keyInput, setKeyInput]     = useState('');
  const [savingKey, setSavingKey]   = useState(false);

  // Preço de modelos (Custos IA) — busca automática diária às 02:00 + edição manual
  const [pricing, setPricing]             = useState<AiModelPricing[]>([]);
  const [editingPrice, setEditingPrice]   = useState<string | null>(null);
  const [priceInInput, setPriceInInput]   = useState('');
  const [priceOutInput, setPriceOutInput] = useState('');
  const [savingPrice, setSavingPrice]     = useState(false);
  const [syncing, setSyncing]             = useState(false);
  const [syncResults, setSyncResults]     = useState<PricingFetchResult[] | null>(null);

  // ── Carregar ────────────────────────────────────────────────────────────────

  const loadAll = useCallback(async () => {
    try {
      const [provRes, chainRes, pricingRes] = await Promise.all([
        api.get<ProviderDef[]>('/ai/providers'),
        api.get<ChainEntry[]>('/ai/chain'),
        api.get<AiModelPricing[]>('/ai/model-pricing'),
      ]);
      setProviders(provRes.data);
      const grouped: Record<Capability, ChainEntry[]> = { STT: [], LLM: [], TTS: [] };
      chainRes.data.forEach(e => {
        const cap = e.capability as Capability;
        if (grouped[cap]) grouped[cap].push(e);
      });
      setChains(grouped);
      setPricing(pricingRes.data);
    } catch {
      showToast('Erro ao carregar configuração de IA', 'error');
    }
  }, []);

  useEffect(() => { if (open) loadAll(); }, [open, loadAll]);

  // ── Salvar chains ───────────────────────────────────────────────────────────

  const saveChains = async () => {
    setSaving(true);
    try {
      await Promise.all(
        (['STT', 'LLM', 'TTS'] as Capability[]).map(cap =>
          api.put(`/ai/chain/${cap}`, chains[cap].map(e => ({ provider: e.provider, modelId: e.modelId })))
        )
      );
      showToast('Chains salvas · ai-agent aplica na próxima chamada', 'success');
    } catch {
      showToast('Erro ao salvar chains', 'error');
    } finally {
      setSaving(false);
    }
  };

  // ── API Key ─────────────────────────────────────────────────────────────────

  const saveKey = async (providerId: string) => {
    if (!keyInput.trim()) return;
    setSavingKey(true);
    try {
      await api.put(`/ai/providers/${providerId}/key`, { apiKey: keyInput.trim() });
      setEditingKey(null);
      setKeyInput('');
      await loadAll();
      showToast('Key salva · modelos disponíveis', 'success');
    } catch {
      showToast('Erro ao salvar key', 'error');
    } finally {
      setSavingKey(false);
    }
  };

  // ── Preço de modelos (Custos IA) ────────────────────────────────────────────

  const startEditPrice = (p: AiModelPricing) => {
    setEditingPrice(p.modelId);
    setPriceInInput(String(p.pricePerMillionInputUsd));
    setPriceOutInput(String(p.pricePerMillionOutputUsd));
  };

  const savePrice = async (modelId: string) => {
    const input = Number(priceInInput);
    const output = Number(priceOutInput);
    if (!Number.isFinite(input) || input < 0 || !Number.isFinite(output) || output < 0) {
      showToast('Preço inválido — use um número maior ou igual a zero', 'error');
      return;
    }
    setSavingPrice(true);
    try {
      await api.put(`/ai/model-pricing/${modelId}`, {
        pricePerMillionInputUsd: input,
        pricePerMillionOutputUsd: output,
      });
      setEditingPrice(null);
      await loadAll();
      showToast('Preço atualizado manualmente', 'success');
    } catch {
      showToast('Erro ao salvar preço', 'error');
    } finally {
      setSavingPrice(false);
    }
  };

  const syncPricesNow = async () => {
    setSyncing(true);
    setSyncResults(null);
    try {
      const res = await api.post<PricingFetchResult[]>('/ai/model-pricing/sync-now', {});
      setSyncResults(res.data);
      await loadAll();
      const failures = res.data.filter(r => !r.success).length;
      if (failures > 0) {
        showToast(`Busca concluída com ${failures} falha(s) — preço anterior mantido`, 'error');
      } else {
        showToast('Preços atualizados a partir da página da Google', 'success');
      }
    } catch {
      showToast('Erro ao disparar a busca de preços', 'error');
    } finally {
      setSyncing(false);
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
    const current = chains[modalCap];
    setChains(prev => ({
      ...prev,
      [modalCap]: [...prev[modalCap], {
        capability: modalCap,
        priority: current.length + 1,
        provider: selProvider.id,
        modelId: selModel.id,
        isEnabled: true,
      }],
    }));
    setModalOpen(false);
  };

  const removeEntry = (cap: Capability, idx: number) => {
    setChains(prev => ({
      ...prev,
      [cap]: prev[cap].filter((_, i) => i !== idx).map((e, i) => ({ ...e, priority: i + 1 })),
    }));
  };

  const showToast = (msg: string, type: 'success' | 'error') => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3000);
  };

  const providerName = (id: string) => providers.find(p => p.id === id)?.name ?? id;

  // ─── Render ──────────────────────────────────────────────────────────────────

  return (
    <>
      {/* Accordion — mesmo padrão das seções Jira/Zabbix */}
      <div className="card" style={{ padding: 0, overflow: 'hidden', marginBottom: 0 }}>

        {/* Header */}
        <button
          onClick={onToggle}
          style={{
            width: '100%', display: 'flex', alignItems: 'center',
            gap: 12, padding: '16px 20px', background: 'none',
            border: 'none', cursor: 'pointer', textAlign: 'left',
            color: 'var(--text-primary)',
          }}
        >
          <span style={{ fontSize: '1.4rem' }}>🤖</span>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 600, fontSize: '0.95rem', display: 'flex', alignItems: 'center', gap: 8 }}>
              Inteligência Artificial
            </div>
            <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>
              Provedores e modelos para STT, LLM e TTS — com fallback automático entre provedores
            </div>
          </div>
          <span style={{
            fontSize: '0.65rem', padding: '2px 8px', borderRadius: 6, fontWeight: 500,
            background: 'rgba(99,102,241,0.08)', color: 'var(--clr-primary)',
            border: '1px solid rgba(99,102,241,0.2)',
          }}>ai-agent</span>
          <span style={{
            color: 'var(--text-muted)', transition: 'transform .2s',
            display: 'inline-block', transform: open ? 'rotate(180deg)' : 'rotate(0)',
          }}>▾</span>
        </button>

        {open && (
          <div style={{ padding: '0 20px 20px', borderTop: '1px solid var(--border-glass)' }}>

            {/* ── Capability chains ── */}
            {CAPABILITIES.map(cap => {
              const entries = chains[cap.id] ?? [];
              return (
                <div key={cap.id} style={{ marginTop: 18 }}>
                  {/* Label da capability */}
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 8,
                    marginBottom: 10,
                  }}>
                    <span style={{ fontSize: '1rem' }}>{cap.icon}</span>
                    <span style={{ fontWeight: 600, fontSize: '0.88rem', color: 'var(--text-primary)' }}>
                      {cap.label}
                    </span>
                    <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                      — {cap.desc}
                    </span>
                  </div>

                  {/* Entradas da chain */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 8 }}>
                    {entries.length === 0 && (
                      <div style={{
                        padding: '10px 14px', borderRadius: 8,
                        background: 'rgba(148,163,184,0.08)',
                        border: '1px dashed var(--border-glass)',
                        fontSize: '0.8rem', color: 'var(--text-muted)', textAlign: 'center',
                      }}>
                        Nenhum provedor configurado
                      </div>
                    )}
                    {entries.map((entry, idx) => (
                      <div key={`${entry.provider}-${entry.modelId}`} style={{
                        display: 'flex', alignItems: 'center', gap: 10,
                        padding: '10px 14px', borderRadius: 8,
                        background: idx === 0
                          ? 'rgba(99,102,241,0.05)'
                          : 'var(--bg-input)',
                        border: idx === 0
                          ? '1px solid rgba(99,102,241,0.25)'
                          : '1px solid var(--border-glass)',
                      }}>
                        <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem', cursor: 'grab' }}>⠿</span>
                        <span style={{
                          fontSize: '0.7rem', fontWeight: 600,
                          padding: '1px 7px', borderRadius: 20, minWidth: 20, textAlign: 'center',
                          background: idx === 0 ? 'rgba(99,102,241,0.12)' : 'rgba(148,163,184,0.12)',
                          color: idx === 0 ? 'var(--clr-primary)' : 'var(--text-secondary)',
                        }}>{idx + 1}</span>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-primary)' }}>
                            {providerName(entry.provider)}
                          </div>
                          <div style={{
                            fontSize: '0.75rem', color: 'var(--text-muted)',
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            fontFamily: '"JetBrains Mono","Fira Code",monospace',
                          }}>
                            {entry.modelId}
                          </div>
                        </div>
                        <span className={`badge ${idx === 0 ? 'badge-info' : 'badge-gray'}`}>
                          {idx === 0 ? 'primário' : `fallback ${idx}`}
                        </span>
                        <button
                          onClick={() => removeEntry(cap.id, idx)}
                          style={{
                            background: 'none', border: 'none', cursor: 'pointer',
                            color: 'var(--text-muted)', fontSize: '1rem',
                            padding: '0 2px', lineHeight: 1,
                          }}
                          title="Remover"
                        >×</button>
                      </div>
                    ))}
                  </div>

                  {/* Botão adicionar */}
                  <button
                    onClick={() => openModal(cap.id)}
                    style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                      width: '100%', padding: '8px',
                      border: '1px dashed var(--border-glass)',
                      borderRadius: 8, background: 'none',
                      cursor: 'pointer', fontSize: '0.8rem', color: 'var(--text-secondary)',
                      transition: 'all 0.15s',
                    }}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--bg-input)')}
                    onMouseLeave={e => (e.currentTarget.style.background = 'none')}
                  >
                    + Adicionar provedor de fallback
                  </button>

                  {/* Divider entre capabilities */}
                  {cap.id !== 'TTS' && (
                    <div style={{ height: 1, background: 'var(--border-glass)', margin: '18px 0 0' }} />
                  )}
                </div>
              );
            })}

            {/* ── Botão salvar chains ── */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--border-glass)' }}>
              <button
                className="btn btn-primary btn-sm"
                onClick={saveChains}
                disabled={saving}
              >
                {saving ? (
                  <><span className="spinner" style={{ width: 14, height: 14, display: 'inline-block', marginRight: 6, verticalAlign: 'middle' }} />Salvando…</>
                ) : 'Salvar chains'}
              </button>
            </div>

            {/* ── API Keys ── */}
            <div style={{ marginTop: 24 }}>
              <div style={{ fontWeight: 600, fontSize: '0.88rem', color: 'var(--text-secondary)', marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
                🔑 API Keys dos provedores
                <span style={{ fontSize: '0.75rem', fontWeight: 400, color: 'var(--text-muted)' }}>
                  — necessária para listar modelos
                </span>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {providers.map(prov => (
                  <div key={prov.id} style={{
                    display: 'flex', alignItems: 'center', gap: 10,
                    padding: '10px 14px', borderRadius: 8,
                    background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                  }}>
                    <span style={{ fontWeight: 600, fontSize: '0.85rem', width: 130, flexShrink: 0 }}>
                      {prov.name}
                    </span>

                    {prov.id === 'local' ? (
                      <span className="badge badge-success">✓ local — sem API key</span>
                    ) : editingKey === prov.id ? (
                      <>
                        <input
                          type="password"
                          value={keyInput}
                          onChange={e => setKeyInput(e.target.value)}
                          placeholder="Cole a API key aqui…"
                          className="form-input"
                          style={{ flex: 1, padding: '6px 12px', fontSize: '0.8rem', fontFamily: '"JetBrains Mono","Fira Code",monospace' }}
                          onKeyDown={e => e.key === 'Enter' && saveKey(prov.id)}
                          autoFocus
                        />
                        <button
                          className="btn btn-primary btn-sm"
                          onClick={() => saveKey(prov.id)}
                          disabled={savingKey}
                        >{savingKey ? '…' : 'Salvar'}</button>
                        <button
                          className="btn btn-ghost btn-sm"
                          onClick={() => { setEditingKey(null); setKeyInput(''); }}
                        >Cancelar</button>
                      </>
                    ) : (
                      <>
                        <span style={{ flex: 1, fontSize: '0.8rem' }}>
                          {prov.hasKey
                            ? <span className="badge badge-success">✓ Key configurada</span>
                            : <span className="badge badge-gray">Sem key — modelos indisponíveis</span>
                          }
                        </span>
                        <button
                          className="btn btn-ghost btn-sm"
                          onClick={() => { setEditingKey(prov.id); setKeyInput(''); }}
                        >Editar</button>
                      </>
                    )}
                  </div>
                ))}
              </div>
            </div>

            {/* ── Preço de modelos (Custos IA) ── */}
            <div style={{ marginTop: 24 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                <span style={{ fontWeight: 600, fontSize: '0.88rem', color: 'var(--text-secondary)' }}>
                  💲 Preço de tokens (Custos IA)
                </span>
                <span style={{ fontSize: '0.75rem', fontWeight: 400, color: 'var(--text-muted)' }}>
                  — usado para estimar o custo de cada chamada (URA e Insights)
                </span>
              </div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 12 }}>
                Buscado automaticamente todo dia às 02:00 na página pública de preços da Google —
                não é uma API oficial, então em caso de falha o último preço válido é mantido e um
                alerta é enviado por Telegram (ver Documentação → Insights). Corrija manualmente
                aqui se desconfiar do valor.
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {pricing.map(p => (
                  <div key={p.modelId} style={{
                    display: 'flex', alignItems: 'center', gap: 10,
                    padding: '10px 14px', borderRadius: 8,
                    background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
                    flexWrap: 'wrap',
                  }}>
                    <span style={{
                      fontWeight: 600, fontSize: '0.8rem', minWidth: 220, flexShrink: 0,
                      fontFamily: '"JetBrains Mono","Fira Code",monospace',
                    }}>
                      {p.modelId}
                    </span>

                    {editingPrice === p.modelId ? (
                      <>
                        <label style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
                          Input ($/1M)
                          <input
                            type="number" min="0" step="0.01"
                            value={priceInInput}
                            onChange={e => setPriceInInput(e.target.value)}
                            className="form-input"
                            style={{ width: 90, marginLeft: 6, padding: '4px 8px', fontSize: '0.8rem' }}
                          />
                        </label>
                        <label style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
                          Output ($/1M)
                          <input
                            type="number" min="0" step="0.01"
                            value={priceOutInput}
                            onChange={e => setPriceOutInput(e.target.value)}
                            className="form-input"
                            style={{ width: 90, marginLeft: 6, padding: '4px 8px', fontSize: '0.8rem' }}
                          />
                        </label>
                        <button
                          className="btn btn-primary btn-sm"
                          onClick={() => savePrice(p.modelId)}
                          disabled={savingPrice}
                        >{savingPrice ? '…' : 'Salvar'}</button>
                        <button
                          className="btn btn-ghost btn-sm"
                          onClick={() => setEditingPrice(null)}
                        >Cancelar</button>
                      </>
                    ) : (
                      <>
                        <span style={{ fontSize: '0.8rem' }}>
                          Input: <strong>${p.pricePerMillionInputUsd.toFixed(2)}</strong> · Output: <strong>${p.pricePerMillionOutputUsd.toFixed(2)}</strong>
                        </span>
                        <span className={`badge ${p.updatedBy === 'auto-fetch' ? 'badge-info' : 'badge-gray'}`} style={{ fontSize: '0.65rem' }}>
                          {p.updatedBy === 'auto-fetch' ? '🤖 busca automática' : `✍️ manual — ${p.updatedBy ?? '?'}`}
                        </span>
                        {p.updatedAt && (
                          <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
                            {new Date(p.updatedAt).toLocaleString('pt-BR')}
                          </span>
                        )}
                        <div style={{ flex: 1 }} />
                        <button
                          className="btn btn-ghost btn-sm"
                          onClick={() => startEditPrice(p)}
                        >Editar</button>
                      </>
                    )}
                  </div>
                ))}
                {pricing.length === 0 && (
                  <div style={{
                    padding: '10px 14px', borderRadius: 8,
                    background: 'rgba(148,163,184,0.08)',
                    border: '1px dashed var(--border-glass)',
                    fontSize: '0.8rem', color: 'var(--text-muted)', textAlign: 'center',
                  }}>
                    Nenhum modelo com preço cadastrado ainda
                  </div>
                )}
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 10, marginTop: 10 }}>
                {syncResults && syncResults.some(r => !r.success) && (
                  <span style={{ fontSize: '0.72rem', color: 'var(--clr-danger)' }}>
                    {syncResults.filter(r => !r.success).map(r => `${r.modelId}: ${r.failureReason}`).join(' · ')}
                  </span>
                )}
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={syncPricesNow}
                  disabled={syncing}
                >
                  {syncing ? (
                    <><span className="spinner" style={{ width: 14, height: 14, display: 'inline-block', marginRight: 6, verticalAlign: 'middle' }} />Buscando…</>
                  ) : '🔄 Buscar preço agora'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* ── Modal: selecionar provedor + modelo ── */}
      {modalOpen && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.55)',
          zIndex: 500, display: 'flex', alignItems: 'center', justifyContent: 'center',
          padding: '1rem', backdropFilter: 'blur(4px)',
        }}>
          <div className="card" style={{ width: '100%', maxWidth: 520, padding: 0 }}>

            {/* Modal header */}
            <div className="card-header" style={{ padding: '16px 20px' }}>
              <span className="card-title">
                Adicionar provedor — {modalCap}
              </span>
              <button
                onClick={() => setModalOpen(false)}
                style={{ background: 'none', border: 'none', fontSize: '1.2rem', cursor: 'pointer', color: 'var(--text-muted)' }}
              >×</button>
            </div>

            <div style={{ padding: '16px 20px' }}>

              {/* Passo 1 — Provedor */}
              <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 10 }}>
                1. Escolha o provedor
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 20 }}>
                {providers.filter(p => p.capabilities.includes(modalCap)).map(prov => {
                  const disabled = !prov.hasKey && prov.id !== 'local';
                  const selected = selProvider?.id === prov.id;
                  return (
                    <div
                      key={prov.id}
                      onClick={() => !disabled && selectProvider(prov)}
                      style={{
                        border: `1px solid ${selected ? 'var(--clr-primary)' : 'var(--border-glass)'}`,
                        background: selected ? 'rgba(99,102,241,0.06)' : 'var(--bg-input)',
                        borderRadius: 8, padding: '10px 12px',
                        cursor: disabled ? 'not-allowed' : 'pointer',
                        opacity: disabled ? .45 : 1,
                        transition: 'all 0.15s',
                      }}
                    >
                      <div style={{ fontWeight: 600, fontSize: '0.85rem' }}>{prov.name}</div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 2 }}>{prov.capabilities.join(' · ')}</div>
                      <div style={{ marginTop: 5 }}>
                        {prov.id === 'local'
                          ? <span className="badge badge-success" style={{ fontSize: '0.65rem' }}>✓ local</span>
                          : prov.hasKey
                            ? <span className="badge badge-success" style={{ fontSize: '0.65rem' }}>✓ key configurada</span>
                            : <span className="badge badge-gray" style={{ fontSize: '0.65rem' }}>⚠ sem key</span>
                        }
                      </div>
                    </div>
                  );
                })}
              </div>

              {/* Passo 2 — Modelo */}
              {(selProvider || modelsLoading) && (
                <>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 10 }}>
                    2. Selecione o modelo
                  </div>
                  <div style={{
                    border: '1px solid var(--border-glass)', borderRadius: 8,
                    overflow: 'hidden', maxHeight: 220, overflowY: 'auto', marginBottom: 8,
                  }}>
                    {modelsLoading ? (
                      <div style={{ padding: '16px', textAlign: 'center', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                        <span className="spinner" style={{ width: 14, height: 14, display: 'inline-block', marginRight: 8, verticalAlign: 'middle' }} />
                        Buscando modelos na API de {selProvider?.name}…
                      </div>
                    ) : models.length === 0 ? (
                      <div style={{ padding: '16px', textAlign: 'center', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                        Nenhum modelo disponível para {modalCap}
                      </div>
                    ) : models.map((model) => (
                      <div
                        key={model.id}
                        onClick={() => setSelModel(model)}
                        style={{
                          padding: '10px 14px', cursor: 'pointer',
                          borderBottom: '1px solid var(--border-glass)',
                          background: selModel?.id === model.id ? 'rgba(99,102,241,0.05)' : 'transparent',
                          transition: 'background 0.1s',
                        }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 3 }}>
                          <span style={{ fontWeight: 600, fontSize: '0.85rem' }}>
                            {model.displayName || model.id}
                          </span>
                          <span style={{ display: 'flex', gap: 4 }}>
                            {model.tags.map(tag => (
                              <span key={tag} className={`badge ${TAG_LABELS[tag]?.cls ?? 'badge-gray'}`} style={{ fontSize: '0.65rem' }}>
                                {TAG_LABELS[tag]?.label ?? tag}
                              </span>
                            ))}
                          </span>
                        </div>
                        {model.description && (
                          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                            {model.description}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </>
              )}
            </div>

            {/* Modal footer */}
            <div style={{
              padding: '14px 20px', borderTop: '1px solid var(--border-glass)',
              display: 'flex', justifyContent: 'flex-end', gap: 8,
              background: 'var(--bg-input)',
            }}>
              <button className="btn btn-ghost btn-sm" onClick={() => setModalOpen(false)}>Cancelar</button>
              <button
                className="btn btn-primary btn-sm"
                onClick={confirmAdd}
                disabled={!selProvider || !selModel}
                style={{ opacity: (!selProvider || !selModel) ? .45 : 1 }}
              >Adicionar</button>
            </div>
          </div>
        </div>
      )}

      {/* Toast */}
      {toast && (
        <div style={{
          position: 'fixed', bottom: '1.5rem', left: '50%', transform: 'translateX(-50%)',
          padding: '10px 20px', borderRadius: 8, fontSize: '0.85rem',
          fontWeight: 500, zIndex: 600,
          background: toast.type === 'success' ? 'var(--clr-success)' : 'var(--clr-danger)',
          color: '#fff', boxShadow: '0 4px 16px rgba(0,0,0,0.15)',
        }}>{toast.msg}</div>
      )}
    </>
  );
};
