import type { AppUser, TotpSetup } from './userModalTypes';

type TotpStep = 'status' | 'setup' | 'confirm' | 'disable';

interface TotpModalProps {
  user: AppUser;
  status: boolean;
  step: TotpStep;
  setup: TotpSetup | null;
  code: string;
  setCode: (v: string) => void;
  msg: string;
  loading: boolean;
  onClose: () => void;
  onStartSetup: () => void;
  onGoToDisableStep: () => void;
  onBackToStatus: () => void;
  onConfirmEnable: () => void;
  onConfirmDisable: () => void;
}

/** Modal de gerenciamento de 2FA (TOTP) de um usuário — status, ativação com QR Code, desativação. */
export function TotpModal({
  user, status, step, setup, code, setCode, msg, loading,
  onClose, onStartSetup, onGoToDisableStep, onBackToStatus, onConfirmEnable, onConfirmDisable,
}: TotpModalProps) {
  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal modal-sm">
        <div className="modal-header">
          <h2>🔐 2FA — {user.username}</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          {loading ? (
            <div className="loading-state"><div className="spinner" />Carregando…</div>
          ) : (
            <>
              {/* ── STATUS ── */}
              {step === 'status' && (
                <>
                  <div style={{
                    textAlign: 'center', padding: '20px',
                    background: status ? 'rgba(72,199,142,0.08)' : 'rgba(0,122,255,0.08)',
                    borderRadius: 10, marginBottom: 16,
                    border: `1px solid ${status ? 'rgba(72,199,142,0.3)' : 'rgba(0,122,255,0.3)'}`,
                  }}>
                    <div style={{ fontSize: '2.5rem', marginBottom: 8 }}>
                      {status ? '🛡️' : '🔓'}
                    </div>
                    <div style={{ fontWeight: 600, fontSize: '1rem', color: status ? '#34c759' : '#4da8ff' }}>
                      {status ? '2FA Ativado' : '2FA Desativado'}
                    </div>
                    <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
                      {status
                        ? 'Este usuário usa verificação em 2 etapas no login.'
                        : 'O login deste usuário usa apenas senha.'}
                    </div>
                  </div>

                  {msg && (
                    <div style={{ padding: '8px 12px', borderRadius: 8, marginBottom: 12, fontSize: '0.85rem',
                      background: msg.startsWith('✅') ? 'rgba(72,199,142,0.1)' : 'rgba(255,107,107,0.1)',
                      color: msg.startsWith('✅') ? '#34c759' : '#ff6b6b',
                      border: `1px solid ${msg.startsWith('✅') ? 'rgba(72,199,142,0.3)' : 'rgba(255,107,107,0.3)'}`,
                    }}>
                      {msg}
                    </div>
                  )}

                  {!status ? (
                    <button className="btn btn-primary" style={{ width: '100%' }} onClick={onStartSetup}>
                      🔐 Configurar 2FA (ativar)
                    </button>
                  ) : (
                    <button
                      className="btn btn-ghost"
                      style={{ width: '100%', borderColor: 'rgba(255,107,107,0.4)', color: '#ff6b6b' }}
                      onClick={onGoToDisableStep}
                    >
                      🚫 Desativar 2FA
                    </button>
                  )}
                </>
              )}

              {/* ── SETUP: exibe QR Code ── */}
              {step === 'setup' && setup && (
                <>
                  <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: 12, lineHeight: 1.6 }}>
                    <strong>1.</strong> Abra o <strong>Google Authenticator</strong> ou outro app TOTP.<br />
                    <strong>2.</strong> Escaneie o QR Code abaixo.<br />
                    <strong>3.</strong> Insira o código de 6 dígitos para confirmar.
                  </div>

                  <div style={{ textAlign: 'center', marginBottom: 16 }}>
                    <img
                      src={setup.qrCodeUrl}
                      alt="QR Code 2FA"
                      style={{ width: 200, height: 200, borderRadius: 8, background: '#fff', padding: 8 }}
                    />
                    <div style={{ marginTop: 8, fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                      Ou insira manualmente: <br />
                      <code style={{ background: 'rgba(255,255,255,0.08)', padding: '2px 8px', borderRadius: 4, fontSize: '0.8rem', letterSpacing: '0.15em' }}>
                        {setup.secret}
                      </code>
                    </div>
                  </div>

                  <div className="form-group">
                    <label className="form-label">Código do app autenticador</label>
                    <input
                      type="text"
                      inputMode="numeric"
                      pattern="[0-9 ]*"
                      maxLength={7}
                      className="form-input"
                      placeholder="000 000"
                      autoFocus
                      autoComplete="one-time-code"
                      style={{ letterSpacing: '0.3em', fontSize: '1.3rem', textAlign: 'center' }}
                      value={code}
                      onChange={e => setCode(e.target.value)}
                    />
                  </div>

                  {msg && (
                    <div style={{ padding: '8px 12px', borderRadius: 8, marginBottom: 8, fontSize: '0.85rem',
                      background: 'rgba(255,107,107,0.1)', color: '#ff6b6b', border: '1px solid rgba(255,107,107,0.3)' }}>
                      {msg}
                    </div>
                  )}
                </>
              )}

              {/* ── DISABLE: confirmar com código ── */}
              {step === 'disable' && (
                <>
                  <div style={{ padding: '12px 16px', borderRadius: 8, marginBottom: 14,
                    background: 'rgba(255,107,107,0.08)', border: '1px solid rgba(255,107,107,0.3)',
                    fontSize: '0.85rem', color: '#ff6b6b', lineHeight: 1.6 }}>
                    ⚠️ Para desativar o 2FA, insira o código atual do app autenticador para confirmar.
                  </div>

                  <div className="form-group">
                    <label className="form-label">Código do app autenticador</label>
                    <input
                      type="text"
                      inputMode="numeric"
                      pattern="[0-9 ]*"
                      maxLength={7}
                      className="form-input"
                      placeholder="000 000"
                      autoFocus
                      style={{ letterSpacing: '0.3em', fontSize: '1.3rem', textAlign: 'center' }}
                      value={code}
                      onChange={e => setCode(e.target.value)}
                    />
                  </div>

                  {msg && (
                    <div style={{ padding: '8px 12px', borderRadius: 8, marginBottom: 8, fontSize: '0.85rem',
                      background: 'rgba(255,107,107,0.1)', color: '#ff6b6b', border: '1px solid rgba(255,107,107,0.3)' }}>
                      {msg}
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </div>

        <div className="modal-footer">
          {step === 'status' && (
            <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
          )}
          {step === 'setup' && (
            <>
              <button className="btn btn-ghost" onClick={onBackToStatus}>← Voltar</button>
              <button
                className="btn btn-primary"
                onClick={onConfirmEnable}
                disabled={loading || code.replace(/\s/g, '').length < 6}
              >
                {loading ? 'Verificando…' : '✅ Ativar 2FA'}
              </button>
            </>
          )}
          {step === 'disable' && (
            <>
              <button className="btn btn-ghost" onClick={onBackToStatus}>← Voltar</button>
              <button
                className="btn btn-ghost"
                style={{ borderColor: 'rgba(255,107,107,0.4)', color: '#ff6b6b' }}
                onClick={onConfirmDisable}
                disabled={loading || code.replace(/\s/g, '').length < 6}
              >
                {loading ? 'Desativando…' : '🚫 Confirmar Desativação'}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
