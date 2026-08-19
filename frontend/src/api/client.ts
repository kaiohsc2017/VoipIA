/**
 * client.ts — Axios client configurado para a API VoipIA.
 *
 * - baseURL: variável de ambiente VITE_API_URL (padrão: http://localhost:8080/api/v1)
 * - Interceptor de request: injeta Bearer token do localStorage
 * - Interceptor de response: redireciona para login em 401
 * - O refresh token vive num cookie httpOnly (setado pelo backend em
 *   /auth/login e /auth/refresh) — nunca em localStorage/JS. withCredentials
 *   é obrigatório para o navegador enviar/receber esse cookie.
 */

import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1';

const api = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
  withCredentials: true,
});

// ---- Request interceptor: injeta JWT ----
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('voipia_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// ---- Response interceptor: 401 → tenta refresh, se falhar limpa sessão ----
let isRefreshing = false;
let failedQueue: Array<{resolve: (value?: unknown) => void, reject: (reason?: any) => void}> = [];

const processQueue = (error: any, token: string | null = null) => {
  failedQueue.forEach(prom => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token);
    }
  });
  failedQueue = [];
};

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    const isAuthEndpoint = originalRequest?.url?.includes('/auth/login') ||
                           originalRequest?.url?.includes('/auth/refresh') ||
                           originalRequest?.url?.includes('/auth/totp');

    if (error.response?.status === 401 && !originalRequest._retry && !isAuthEndpoint) {
      if (isRefreshing) {
        return new Promise(function(resolve, reject) {
          failedQueue.push({resolve, reject});
        }).then(token => {
          originalRequest.headers.Authorization = 'Bearer ' + token;
          return api(originalRequest);
        }).catch(err => {
          return Promise.reject(err);
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        // Sem body: o refresh token vai no cookie httpOnly (withCredentials).
        const { data } = await axios.post(`${BASE_URL}/auth/refresh`, {}, { withCredentials: true });
        localStorage.setItem('voipia_token', data.token);

        processQueue(null, data.token);
        originalRequest.headers.Authorization = `Bearer ${data.token}`;
        return api(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        logout();
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    // 401 que já passou pelo retry (token novo também rejeitado, sessão revogada,
    // clock skew): não deixar a sessão "presa" — força logout e redireciona ao login.
    if (error.response?.status === 401 && !isAuthEndpoint) {
      logout();
    }

    return Promise.reject(error);
  },
);

/**
 * Revoga o refresh token no backend e limpa o cookie httpOnly (não há como
 * o JS limpar um cookie httpOnly diretamente). Best-effort — não bloqueia
 * o logout local se a rede falhar. Não dispara 'asteriskia:logout' (quem
 * chama já está tratando o encerramento da sessão local).
 */
export function revokeSession() {
  return axios.post(`${BASE_URL}/auth/logout`, {}, { withCredentials: true }).catch(() => {});
}

/**
 * Extrai a mensagem de erro de uma resposta Axios (`{error}` ou `{message}` do backend),
 * caindo no `fallback` dado se o erro não tiver esse formato — substitui o padrão repetido
 * `catch (err: any) { alert(err?.response?.data?.error ?? err?.response?.data?.message ?? '...') }`
 * espalhado pelos componentes.
 */
export function getErrorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { error?: string; message?: string } | undefined;
    return data?.error ?? data?.message ?? fallback;
  }
  return fallback;
}

function logout() {
  localStorage.removeItem('voipia_token');
  localStorage.removeItem('voipia_user');
  revokeSession();
  window.dispatchEvent(new Event('voipia:logout'));
}

/**
 * Decodifica o payload de um JWT sem validar assinatura — é só um hint de UI
 * (esconder nav/rotas admin), a autorização real continua sendo aplicada
 * pelo backend em toda requisição.
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

/**
 * Extrai a claim "role" do JWT — legado (RBAC binário). Tokens antigos sem a
 * claim ou payload inválido caem em 'USER' (o menos privilegiado), igual ao
 * JwtService.java.
 */
export function getRoleFromToken(token: string | null): 'ADMIN' | 'USER' {
  if (!token) return 'USER';
  try {
    return decodeTokenPayload(token).role === 'ADMIN' ? 'ADMIN' : 'USER';
  } catch {
    return 'USER';
  }
}

/**
 * Extrai a claim "perm" (grupos de acesso granulares — V22): mapa
 * {resource_key: "r"|"w"|"rw"}. Tokens emitidos antes do RBAC granular não
 * têm essa claim — retorna mapa vazio, e quem chama deve tratar ADMIN (role
 * legada) como acesso total via canRead/canWrite abaixo.
 */
export function getPermissionsFromToken(token: string | null): Record<string, string> {
  if (!token) return {};
  try {
    const perm = decodeTokenPayload(token).perm;
    return perm && typeof perm === 'object' ? (perm as Record<string, string>) : {};
  } catch {
    return {};
  }
}

/**
 * ADMIN (role legada) sempre pode — isso é o que mantém uma sessão já aberta
 * antes do deploy do RBAC granular (token só com "role", sem "perm") ainda
 * enxergando a navegação certa até relogar, espelhando o fallback dual-emit
 * do SecurityConfig.java (hasAnyAuthority ROLE_ADMIN OU PERM_*).
 */
export function canRead(role: 'ADMIN' | 'USER', perms: Record<string, string>, resource: string): boolean {
  return role === 'ADMIN' || (perms[resource]?.includes('r') ?? false);
}

export function canWrite(role: 'ADMIN' | 'USER', perms: Record<string, string>, resource: string): boolean {
  return role === 'ADMIN' || (perms[resource]?.includes('w') ?? false);
}

export default api;
