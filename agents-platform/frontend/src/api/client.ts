/**
 * client.ts — dois clientes Axios, porque Agentes fala com dois backends:
 *
 * - `api` (default export): backend próprio da Plataforma de Agentes (FastAPI,
 *   agents-platform/backend/), acessado via Caddy `@agents-api` — que faz
 *   `strip_prefix /agents` antes de repassar. baseURL relativo `/agents`
 *   (mesma origem), paths internos `/api/agents`, `/api/servers` etc,
 *   exatamente como o app legado (`window.AGENTS_API || '/agents'`).
 * - `telecomApi`: backend do Telecom (Spring Boot) — só para login
 *   (`POST /auth/login`) e para obter o token de streaming do WebSocket de
 *   alertas (`POST /auth/streaming-token`). Agentes não tem login próprio
 *   nem refresh-token — reusa o JWT do Telecom (ver agents-backend/auth.py).
 *
 * Diferente do Telecom/Insights: o backend FastAPI não tem envelope de
 * resposta fixo, pagina por offset/limit (não page/size), e erros vêm em
 * `{detail: string | Array<{msg}>}` — nunca `{error}`/`{message}`.
 */

import axios, { type AxiosInstance } from 'axios';

const AGENTS_BASE_URL = import.meta.env.VITE_AGENTS_API_URL ?? '/agents';
const TELECOM_BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1';

const api = axios.create({
  baseURL: AGENTS_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
});

// Login precisa do cookie httpOnly de refresh do Telecom (withCredentials);
// as demais chamadas nesta instância (streaming-token) só usam o Bearer.
const telecomApi = axios.create({
  baseURL: TELECOM_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
  withCredentials: true,
});

function attachAuth(instance: AxiosInstance) {
  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem('voipia_token') ?? localStorage.getItem('asteriskia_token');
    if (token) config.headers.Authorization = `Bearer ${token}`;
    return config;
  });
}
attachAuth(api);
attachAuth(telecomApi);

// 401 do backend de Agentes → sessão local encerrada. Sem tentativa de
// refresh aqui (o agents-backend não emite/valida refresh token, só o JWT
// principal) — mesma simplicidade do app legado, que também não tentava
// refresh nesse caso; o Telecom/Insights (mesma aba/sessão) continuam
// tratando o refresh normalmente do lado deles.
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) logout();
    return Promise.reject(error);
  },
);

function logout() {
  localStorage.removeItem('voipia_token');
  localStorage.removeItem('voipia_user');
  localStorage.removeItem('asteriskia_token');
  localStorage.removeItem('asteriskia_user');
  window.dispatchEvent(new Event('voipia:logout'));
  window.dispatchEvent(new Event('asteriskia:logout'));
}

/**
 * Extrai a mensagem de erro de uma resposta Axios do backend de Agentes —
 * `detail` pode ser uma string (erros manuais: 400/401/403/404/413) ou um
 * array de objetos `{msg}` (422 automático do Pydantic). Cai no `fallback`
 * dado se não reconhecer o formato.
 */
export function getErrorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const detail = (error.response?.data as { detail?: string | Array<{ msg?: string }> } | undefined)?.detail;
    if (typeof detail === 'string') return detail;
    if (Array.isArray(detail) && detail.length > 0) {
      const joined = detail.map((d) => d.msg).filter(Boolean).join('; ');
      if (joined) return joined;
    }
  }
  return fallback;
}

/**
 * Decodifica o payload de um JWT sem validar assinatura — é só um hint de UI
 * (esconder nav/botões admin), a autorização real continua sendo aplicada
 * pelo agents-backend (auth.py require_permission/require_admin) em toda
 * requisição.
 */
export function decodeTokenPayload(token: string): Record<string, unknown> {
  const payload = token.split('.')[1];
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
  const json = decodeURIComponent(
    atob(base64)
      .split('')
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join(''),
  );
  return JSON.parse(json);
}

export function getRoleFromToken(token: string | null): 'ADMIN' | 'USER' {
  if (!token) return 'USER';
  try {
    return decodeTokenPayload(token).role === 'ADMIN' ? 'ADMIN' : 'USER';
  } catch {
    return 'USER';
  }
}

export function getPermissionsFromToken(token: string | null): Record<string, string> {
  if (!token) return {};
  try {
    const perm = decodeTokenPayload(token).perm;
    return perm && typeof perm === 'object' ? (perm as Record<string, string>) : {};
  } catch {
    return {};
  }
}

/** ADMIN (role legada) sempre pode — mesmo fallback dual-emit do backend. */
export function canRead(role: 'ADMIN' | 'USER', perms: Record<string, string>, resource: string): boolean {
  return role === 'ADMIN' || (perms[resource]?.includes('r') ?? false);
}

export function canWrite(role: 'ADMIN' | 'USER', perms: Record<string, string>, resource: string): boolean {
  return role === 'ADMIN' || (perms[resource]?.includes('w') ?? false);
}

export { telecomApi };
export default api;
