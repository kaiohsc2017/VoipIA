import { useState } from 'react';
// Login/2FA vivem no backend Telecom, não no backend de Agentes — usa
// telecomApi (não o `api` default, que aponta pro agents-backend).
import { telecomApi as api, getErrorMessage } from '../api/client';
import type { LoginRequest, LoginResponse } from '../api/types';

interface LoginProps {
  onLogin: (token: string, username: string) => void;
}

export default function Login({ onLogin }: LoginProps) {
  const [form, setForm] = useState<LoginRequest>({ username: '', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  // --- 2FA state ---
  const [requiresTotp, setRequiresTotp] = useState(false);
  const [tempToken, setTempToken]       = useState('');
  const [totpCode, setTotpCode]         = useState('');
  const [totpDisplayName, setTotpDisplayName] = useState('');

  // --- Oferta de MFA no primeiro login (opcional) ---
  const [mfaStep, setMfaStep] = useState<'none' | 'offer' | 'setup'>('none');
  const [mfaSetupData, setMfaSetupData] = useState<{ secret: string; qrCodeUrl: string } | null>(null);
  const [mfaCode, setMfaCode] = useState('');
  const [mfaMsg, setMfaMsg] = useState('');

  // Guarda a sessão e entra no app — ou, se for o primeiro login, oferece MFA antes.
  const finishLogin = (token: string, firstLoginCompleted?: boolean) => {
    const cleanUser = form.username.trim();
    localStorage.setItem('voipia_token', token);
    localStorage.setItem('voipia_user', cleanUser);
    localStorage.setItem('asteriskia_token', token);
    localStorage.setItem('asteriskia_user', cleanUser);
    if (!firstLoginCompleted) {
      setMfaStep('offer');
      return;
    }
    onLogin(token, cleanUser);
  };

  // --- Etapa 1: usuário + senha ---
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    const payload = {
      username: form.username.trim(),
      password: form.password,
    };
    try {
      const { data } = await api.post<LoginResponse & { requiresTotp?: boolean; tempToken?: string; displayName?: string }>('/auth/login', payload);

      if (data.requiresTotp && data.tempToken) {
        // 2FA ativo → mostra campo de código TOTP
        setTempToken(data.tempToken);
        setTotpDisplayName(data.displayName ?? payload.username);
        setRequiresTotp(true);
        return;
      }

      // Login normal (sem 2FA). O refresh token vai num cookie httpOnly
      // setado pelo backend — nunca chega aqui em JS.
      finishLogin(data.token!, data.firstLoginCompleted);
    } catch (err) {
      const msg = getErrorMessage(err, 'Credenciais inválidas. Tente novamente.');
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // --- Etapa 2: código TOTP ---
  const handleTotp = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const { data } = await api.post<{ token: string; extension: number; displayName: string; firstLoginCompleted?: boolean }>('/auth/totp/verify', {
        tempToken,
        code: totpCode.replace(/\s/g, ''),
      });
      finishLogin(data.token, data.firstLoginCompleted);
    } catch (err) {
      const msg = getErrorMessage(err, 'Código inválido ou expirado.');
      setError(msg);
      setTotpCode('');
    } finally {
      setLoading(false);
    }
  };

  const cancelTotp = () => {
    setRequiresTotp(false);
    setTempToken('');
    setTotpCode('');
    setError('');
  };

  // --- Oferta de MFA (primeiro login) ---
  const skipMfaOffer = async () => {
    try { await api.post('/auth/totp/first-login-complete'); } catch { /* não bloqueia o login por isso */ }
    onLogin(localStorage.getItem('asteriskia_token')!, form.username);
  };

  const startMfaSetup = async () => {
    setLoading(true);
    setMfaMsg('');
    try {
      const { data } = await api.post<{ secret: string; qrCodeUrl: string }>('/auth/totp/setup');
      setMfaSetupData(data);
      setMfaStep('setup');
    } catch {
      setMfaMsg('Erro ao iniciar configuração do 2FA. Tente novamente ou pule por agora.');
    } finally {
      setLoading(false);
    }
  };

  const confirmMfaSetup = async () => {
    const code = mfaCode.replace(/\s/g, '');
    if (code.length !== 6) { setMfaMsg('Digite os 6 dígitos do código.'); return; }
    setLoading(true);
    setMfaMsg('');
    try {
      await api.post('/auth/totp/enable', { code });
      onLogin(localStorage.getItem('asteriskia_token')!, form.username);
    } catch {
      setMfaMsg('Código inválido. Verifique o app autenticador e tente novamente.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        {/* Logo */}
        <div className="login-logo">
          <div className="login-logo-icon">A★</div>
          <span className="login-logo-text">VoipIA</span>
        </div>

        {mfaStep === 'offer' ? (
          /* ── Oferta de MFA no primeiro login (opcional) ── */
          <>
            <h1 className="login-title">Ative a verificação em 2 etapas</h1>
            <p className="login-subtitle">
              Deixe sua conta mais segura com um código extra a cada login. É opcional — você pode configurar depois em "Usuários".
            </p>
            {mfaMsg && (
              <div className="login-error"><span>⚠️</span><span>{mfaMsg}</span></div>
            )}
            <button type="button" className="btn login-btn" onClick={startMfaSetup} disabled={loading}>
              {loading ? 'Carregando…' : '🔐 Configurar agora'}
            </button>
            <button type="button" className="btn btn-ghost" onClick={skipMfaOffer} style={{ width: '100%', marginTop: 10 }} disabled={loading}>
              Pular por agora
            </button>
          </>
        ) : mfaStep === 'setup' && mfaSetupData ? (
          /* ── Configuração de MFA: QR Code + confirmação ── */
          <>
            <h1 className="login-title">Escaneie o QR Code</h1>
            <p className="login-subtitle">
              Abra o Google Authenticator (ou outro app TOTP), escaneie o código abaixo e digite o código gerado.
            </p>
            <div style={{ textAlign: 'center', marginBottom: 16 }}>
              <img src={mfaSetupData.qrCodeUrl} alt="QR Code 2FA" style={{ width: 180, height: 180, borderRadius: 8, background: '#fff', padding: 8 }} />
              <div style={{ marginTop: 8, fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                Ou insira manualmente: <code style={{ background: 'rgba(255,255,255,0.08)', padding: '2px 8px', borderRadius: 4 }}>{mfaSetupData.secret}</code>
              </div>
            </div>
            {mfaMsg && (
              <div className="login-error"><span>⚠️</span><span>{mfaMsg}</span></div>
            )}
            <div className="form-group" style={{ marginBottom: 16 }}>
              <input
                type="text" inputMode="numeric" pattern="[0-9 ]*" maxLength={7}
                className="form-input" placeholder="000 000" autoFocus autoComplete="one-time-code"
                style={{ letterSpacing: '0.3em', fontSize: '1.4rem', textAlign: 'center' }}
                value={mfaCode} onChange={e => setMfaCode(e.target.value)}
              />
            </div>
            <button type="button" className="btn login-btn" onClick={confirmMfaSetup} disabled={loading || mfaCode.replace(/\s/g, '').length < 6}>
              {loading ? 'Verificando…' : '✅ Ativar 2FA'}
            </button>
            <button type="button" className="btn btn-ghost" onClick={skipMfaOffer} style={{ width: '100%', marginTop: 10 }} disabled={loading}>
              Pular por agora
            </button>
          </>
        ) : !requiresTotp ? (
          /* ── Etapa 1: usuário + senha ── */
          <>
            <h1 className="login-title">Bem-vindo de volta</h1>
            <p className="login-subtitle">Faça login para acessar o painel de monitoramento</p>

            {error && (
              <div className="login-error">
                <span>⚠️</span>
                <span>{error}</span>
              </div>
            )}

            <form onSubmit={handleSubmit}>
              <div className="form-group">
                <label className="form-label" htmlFor="username">Usuário</label>
                <input
                  id="username"
                  type="text"
                  className="form-input"
                  placeholder="admin"
                  autoComplete="username"
                  value={form.username}
                  onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                  required
                />
              </div>

              <div className="form-group" style={{ marginBottom: 24 }}>
                <label className="form-label" htmlFor="password">Senha</label>
                <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
                  <input
                    id="password"
                    type={showPassword ? 'text' : 'password'}
                    className="form-input"
                    placeholder="••••••••"
                    autoComplete="current-password"
                    value={form.password}
                    onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                    style={{ paddingRight: 40 }}
                    required
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(v => !v)}
                    title={showPassword ? 'Ocultar senha' : 'Ver senha'}
                    style={{
                      position: 'absolute',
                      right: 10,
                      background: 'none',
                      border: 'none',
                      cursor: 'pointer',
                      fontSize: '1.1rem',
                      opacity: 0.7,
                      padding: 4,
                      display: 'flex',
                      alignItems: 'center',
                      color: 'inherit',
                    }}
                  >
                    {showPassword ? '🙈' : '👁️'}
                  </button>
                </div>
              </div>

              <button type="submit" className="btn login-btn" disabled={loading}>
                {loading ? (
                  <><span className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }} /> Autenticando…</>
                ) : (
                  'Entrar no Sistema'
                )}
              </button>
            </form>
          </>
        ) : (
          /* ── Etapa 2: código TOTP ── */
          <>
            <h1 className="login-title">Verificação em 2 etapas</h1>
            <p className="login-subtitle">
              Olá, <strong>{totpDisplayName}</strong>! Abra seu app autenticador e insira o código de 6 dígitos.
            </p>

            {error && (
              <div className="login-error">
                <span>⚠️</span>
                <span>{error}</span>
              </div>
            )}

            <form onSubmit={handleTotp}>
              <div className="form-group" style={{ marginBottom: 24 }}>
                <label className="form-label" htmlFor="totp-code">Código do Autenticador</label>
                <input
                  id="totp-code"
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9 ]*"
                  maxLength={7}
                  className="form-input"
                  placeholder="000 000"
                  autoComplete="one-time-code"
                  autoFocus
                  style={{ letterSpacing: '0.3em', fontSize: '1.4rem', textAlign: 'center' }}
                  value={totpCode}
                  onChange={e => setTotpCode(e.target.value)}
                  required
                />
              </div>

              <button type="submit" className="btn login-btn" disabled={loading || totpCode.replace(/\s/g, '').length < 6}>
                {loading ? (
                  <><span className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }} /> Verificando…</>
                ) : (
                  '🔐 Verificar Código'
                )}
              </button>

              <button
                type="button"
                className="btn btn-ghost"
                onClick={cancelTotp}
                style={{ width: '100%', marginTop: 10 }}
              >
                ← Voltar ao login
              </button>
            </form>
          </>
        )}

        <p style={{ textAlign: 'center', marginTop: 24, fontSize: '0.75rem', color: 'var(--text-muted)' }}>
          Sistema VoipIA — Asterisk + IA
        </p>
      </div>
    </div>
  );
}
