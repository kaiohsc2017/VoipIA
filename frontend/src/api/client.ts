/**
 * client.ts — Axios client configurado para a API AsteriskIA.
 *
 * - baseURL: variável de ambiente VITE_API_URL (padrão: http://localhost:8080/api/v1)
 * - Interceptor de request: injeta Bearer token do localStorage
 * - Interceptor de response: redireciona para login em 401
 */

import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1';

const api = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
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

      const refreshToken = localStorage.getItem('asteriskia_refresh_token');
      if (!refreshToken) {
        processQueue(error, null);
        isRefreshing = false;
        logout();
        return Promise.reject(error);
      }

      try {
        const { data } = await axios.post(`${BASE_URL}/auth/refresh`, { refreshToken });
        localStorage.setItem('asteriskia_token', data.token);
        localStorage.setItem('asteriskia_refresh_token', data.refreshToken);
        
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

    return Promise.reject(error);
  },
);

function logout() {
  localStorage.removeItem('asteriskia_token');
  localStorage.removeItem('asteriskia_refresh_token');
  localStorage.removeItem('asteriskia_user');
  window.dispatchEvent(new Event('asteriskia:logout'));
}

export default api;
