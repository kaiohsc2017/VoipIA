import { useState } from 'react';
import api from '../api/client';
import type { LoginRequest, LoginResponse } from '../api/types';

interface LoginProps {
  onLogin: (token: string, username: string) => void;
}

export default function Login({ onLogin }: LoginProps) {
  const [form, setForm] = useState<LoginRequest>({ username: '', password: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  // --- 2FA state ---
  const [requiresTotp, setRequiresTotp] = useState(false);
  const [tempToken, setTempToken]       = useState('');
  const [totpCode, setTotpCode]         = useState('');
  const [totpDisplayName, setTotpDisplayName] = useState('');

  // --- Etapa 1: usuário + senha ---
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const { data } = await api.post<LoginResponse & { refreshToken?: string; requiresTotp?: boolean; tempToken?: string; displayName?: string }>('/auth/login', form);

      if (data.requiresTotp && data.tempToken) {
        // 2FA ativo → mostra campo de código TOTP
        setTempToken(data.tempToken);
        setTotpDisplayName(data.displayName ?? form.username);
        setRequiresTotp(true);
        return;
      }

      // Login normal (sem 2FA)
      localStorage.setItem('asteriskia_token', data.token!);
      localStorage.setItem('asteriskia_refresh_token', data.refreshToken!);
      localStorage.setItem('asteriskia_user', form.username);
      onLogin(data.token!, form.username);
    } catch (err: any) {
      const msg = err.response?.data?.error ?? err.response?.data?.message ?? 'Credenciais inválidas. Tente novamente.';
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
      const { data } = await api.post<{ token: string; refreshToken: string; extension: number; displayName: string }>('/auth/totp/verify', {
        tempToken,
        code: totpCode.replace(/\s/g, ''),
      });
      localStorage.setItem('asteriskia_token', data.token);
      localStorage.setItem('asteriskia_refresh_token', data.refreshToken);
      localStorage.setItem('asteriskia_user', form.username);
      onLogin(data.token, form.username);
    } catch (err: any) {
      const msg = err.response?.data?.error ?? 'Código inválido ou expirado.';
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

  return (
    <div className="login-page">
      <div className="login-card">
        {/* Logo */}
        <div className="login-logo">
          <div className="login-logo-icon">A★</div>
          <span className="login-logo-text">AsteriskIA</span>
        </div>

        {!requiresTotp ? (
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
                <input
                  id="password"
                  type="password"
                  className="form-input"
                  placeholder="••••••••"
                  autoComplete="current-password"
                  value={form.password}
                  onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                  required
                />
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
          Sistema AsteriskIA — Asterisk + IA
        </p>
      </div>
    </div>
  );
}
