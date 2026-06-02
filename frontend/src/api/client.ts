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

// ---- Response interceptor: 401 → limpa sessão ----
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('asteriskia_token');
      localStorage.removeItem('asteriskia_user');
      // dispara evento para o App.tsx redirecionar para login
      window.dispatchEvent(new Event('asteriskia:logout'));
    }
    return Promise.reject(error);
  },
);

export default api;
