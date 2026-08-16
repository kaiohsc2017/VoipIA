/**
 * useAuthSession.ts — deriva role/permissões do token JWT em sessão, consolidando a
 * leitura de localStorage + getRoleFromToken + getPermissionsFromToken + canRead/canWrite
 * que estava duplicada em várias telas (Operadoras, Cadastro0800, Linhas, App).
 */
import { canRead, canWrite, getPermissionsFromToken, getRoleFromToken } from '../api/client';

export interface AuthSession {
  role: 'ADMIN' | 'USER';
  perms: Record<string, string>;
  hasRead: (resource: string) => boolean;
  hasWrite: (resource: string) => boolean;
}

/** Deriva a sessão de autorização a partir de um token JWT já obtido (ex: no App.tsx). */
export function authSessionFromToken(token: string | null): AuthSession {
  const role = getRoleFromToken(token);
  const perms = getPermissionsFromToken(token);
  return {
    role,
    perms,
    hasRead: (resource: string) => canRead(role, perms, resource),
    hasWrite: (resource: string) => canWrite(role, perms, resource),
  };
}

/** Lê o token de sessão do localStorage e deriva role/permissões — uso em telas que só leem. */
export function useAuthSession(): AuthSession {
  return authSessionFromToken(localStorage.getItem('voipia_token'));
}
