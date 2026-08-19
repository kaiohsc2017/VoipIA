import React, { useEffect, useState } from 'react';
import api from '../api/client';

interface SsoConfigData {
  id?: number;
  providerName: string;
  displayName: string;
  clientId: string;
  clientSecret?: string;
  tenantId: string;
  redirectUri: string;
  autoProvisionUsers: boolean;
  isActive: boolean;
}

interface SsoSettingsPanelProps {
  open: boolean;
  onToggle: () => void;
}

export const SsoSettingsPanel: React.FC<SsoSettingsPanelProps> = ({ open, onToggle }) => {
  const [config, setConfig] = useState<SsoConfigData>({
    providerName: 'MICROSOFT_ENTRA',
    displayName: 'Microsoft 365 / Entra ID',
    clientId: '',
    clientSecret: '',
    tenantId: 'common',
    redirectUri: window.location.origin + '/login',
    autoProvisionUsers: true,
    isActive: false,
  });
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [showSecret, setShowSecret] = useState(false);
  const [toast, setToast] = useState<{ msg: string; type: 'success' | 'error' } | null>(null);

  const showToast = (type: 'success' | 'error', msg: string) => {
    setToast({ type, msg });
    setTimeout(() => setToast(null), 4000);
  };

  const loadConfig = async () => {
    setLoading(true);
    try {
      const res = await api.get<any>('/auth/sso/config');
      if (res.data) {
        setConfig(prev => ({
          ...prev,
          displayName: res.data.displayName || prev.displayName,
          isActive: res.data.enabled ?? false,
        }));
      }
    } catch {
      // Endpoint pode retornar default se ainda não salvo
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) {
      loadConfig();
    }
  }, [open]);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      await api.put('/auth/sso/admin/config', {
        displayName: config.displayName,
        clientId: config.clientId,
        clientSecret: config.clientSecret,
        tenantId: config.tenantId,
        redirectUri: config.redirectUri,
        autoProvisionUsers: config.autoProvisionUsers,
        isActive: config.isActive,
      });
      showToast('success', 'Configurações de SSO Microsoft Entra salvas com sucesso!');
    } catch (err: any) {
      showToast('error', err?.response?.data?.message || 'Erro ao salvar configurações de SSO.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{
      background: 'rgba(255,255,255,0.03)',
      border: '1px solid rgba(255,255,255,0.08)',
      borderRadius: 12,
      overflow: 'hidden',
      transition: 'border-color 0.2s',
    }}>
      {toast && (
        <div style={{
          position: 'fixed', top: 20, right: 24, zIndex: 9999,
          padding: '12px 20px', borderRadius: 10,
          background: toast.type === 'success' ? 'rgba(52,199,89,0.15)' : 'rgba(255,107,107,0.15)',
          border: `1px solid ${toast.type === 'success' ? '#34c759' : '#ff6b6b'}`,
          color: toast.type === 'success' ? '#34c759' : '#ff6b6b',
          backdropFilter: 'blur(12px)', fontSize: '0.875rem',
        }}>
          {toast.type === 'success' ? '✅' : '❌'} {toast.msg}
        </div>
      )}

      {/* Header colapsável */}
      <div
        onClick={onToggle}
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 20px', cursor: 'pointer', userSelect: 'none',
          background: open ? 'rgba(255,255,255,0.02)' : 'transparent',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ fontSize: '1.4rem' }}>🛡️</span>
          <div>
            <div style={{ fontWeight: 600, fontSize: '1rem', display: 'flex', alignItems: 'center', gap: 8 }}>
              SSO & Identidade Corporativa (Microsoft Entra ID)
              <span style={{
                fontSize: '0.72rem', padding: '2px 8px', borderRadius: 6,
                background: config.isActive ? 'rgba(52,199,89,0.15)' : 'rgba(255,255,255,0.08)',
                color: config.isActive ? '#34c759' : '#94a3b8',
                border: `1px solid ${config.isActive ? 'rgba(52,199,89,0.3)' : 'rgba(255,255,255,0.1)'}`,
              }}>
                {config.isActive ? 'ATIVO' : 'INATIVO'}
              </span>
            </div>
            <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginTop: 2 }}>
              Autenticação unificada via OpenID Connect / SAML, com provisionamento automático de ramal SIP.
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ fontSize: '0.85rem', color: '#94a3b8' }}>{open ? '▲ Recolher' : '▼ Expandir'}</span>
        </div>
      </div>

      {/* Conteúdo do painel */}
      {open && (
        <form onSubmit={handleSave} style={{ padding: '20px', borderTop: '1px solid rgba(255,255,255,0.06)' }}>
          {loading ? (
            <div style={{ padding: '20px', textAlign: 'center', color: '#94a3b8' }}>Carregando dados de SSO…</div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 16 }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 500, marginBottom: 6, color: '#cbd5e1' }}>
                  Nome de Exibição no Login
                </label>
                <input
                  type="text"
                  className="form-input"
                  value={config.displayName}
                  onChange={e => setConfig({ ...config, displayName: e.target.value })}
                  placeholder="Microsoft 365 / Entra ID"
                  required
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 500, marginBottom: 6, color: '#cbd5e1' }}>
                  Application (Client) ID
                </label>
                <input
                  type="text"
                  className="form-input"
                  value={config.clientId}
                  onChange={e => setConfig({ ...config, clientId: e.target.value })}
                  placeholder="00000000-0000-0000-0000-000000000000"
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 500, marginBottom: 6, color: '#cbd5e1' }}>
                  Directory (Tenant) ID
                </label>
                <input
                  type="text"
                  className="form-input"
                  value={config.tenantId}
                  onChange={e => setConfig({ ...config, tenantId: e.target.value })}
                  placeholder="common ou seu-tenant-id"
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 500, marginBottom: 6, color: '#cbd5e1' }}>
                  Client Secret
                </label>
                <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
                  <input
                    type={showSecret ? 'text' : 'password'}
                    className="form-input"
                    value={config.clientSecret || ''}
                    onChange={e => setConfig({ ...config, clientSecret: e.target.value })}
                    placeholder="••••••••••••••••"
                    style={{ paddingRight: 40 }}
                  />
                  <button
                    type="button"
                    onClick={() => setShowSecret(!showSecret)}
                    style={{
                      position: 'absolute', right: 8, background: 'none', border: 'none',
                      cursor: 'pointer', opacity: 0.7, color: 'inherit',
                    }}
                  >
                    {showSecret ? '🙈' : '👁️'}
                  </button>
                </div>
              </div>

              <div style={{ gridColumn: '1 / -1' }}>
                <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 500, marginBottom: 6, color: '#cbd5e1' }}>
                  Redirect URI de Retorno
                </label>
                <input
                  type="text"
                  className="form-input"
                  value={config.redirectUri}
                  onChange={e => setConfig({ ...config, redirectUri: e.target.value })}
                  placeholder="https://app.voiphash.com.br/login"
                />
              </div>

              <div style={{ gridColumn: '1 / -1', display: 'flex', flexWrap: 'wrap', gap: 24, padding: '12px 0' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: '0.875rem' }}>
                  <input
                    type="checkbox"
                    checked={config.autoProvisionUsers}
                    onChange={e => setConfig({ ...config, autoProvisionUsers: e.target.checked })}
                  />
                  Provisionar automaticamente novos usuários e ramais SIP WebRTC no 1º login
                </label>

                <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: '0.875rem' }}>
                  <input
                    type="checkbox"
                    checked={config.isActive}
                    onChange={e => setConfig({ ...config, isActive: e.target.checked })}
                  />
                  <strong>Habilitar SSO Microsoft Entra ID na tela de Login</strong>
                </label>
              </div>

              <div style={{ gridColumn: '1 / -1', display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 12 }}>
                <button
                  type="button"
                  onClick={async () => {
                    try {
                      const res = await api.get<string>('/auth/sso/authorize-url');
                      if (res.data) {
                        window.open(res.data, '_blank');
                      }
                    } catch {
                      showToast('error', 'Não foi possível gerar URL de teste OIDC.');
                    }
                  }}
                  className="btn btn-secondary"
                >
                  🔗 Testar Fluxo de Autorização
                </button>

                <button
                  type="submit"
                  disabled={saving}
                  className="btn btn-primary"
                >
                  {saving ? 'Gravando…' : '💾 Salvar Configurações de SSO'}
                </button>
              </div>
            </div>
          )}
        </form>
      )}
    </div>
  );
};
