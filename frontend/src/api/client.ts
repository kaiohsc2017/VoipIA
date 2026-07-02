/**
 * client.ts — Axios client configurado para a API AsteriskIA.
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
  const token = localStorage.getItem('asteriskia_token');
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

    if (error.response?.status === 401 && !originalRequest._retry) {
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
        localStorage.setItem('asteriskia_token', data.token);

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
    if (error.response?.status === 401) {
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

function logout() {
  localStorage.removeItem('asteriskia_token');
  localStorage.removeItem('asteriskia_user');
  revokeSession();
  window.dispatchEvent(new Event('asteriskia:logout'));
}

export default api;
