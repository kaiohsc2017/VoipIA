/**
 * useAuthSession.ts — deriva role/permissões do token JWT em sessão. Mesmo
 * padrão de insights-platform/frontend/src/hooks/useAuthSession.ts, resources
 * no namespace `agents.*`.
 */
import { canRead, canWrite, getPermissionsFromToken, getRoleFromToken } from '../api/client';

export interface AuthSession {
  role: 'ADMIN' | 'USER';
  perms: Record<string, string>;
  hasRead: (resource: string) => boolean;
  hasWrite: (resource: string) => boolean;
}

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

export function useAuthSession(): AuthSession {
  return authSessionFromToken(localStorage.getItem('voipia_token') ?? localStorage.getItem('asteriskia_token'));
}
