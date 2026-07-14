/**
 * userModalTypes.ts — tipos e helpers compartilhados entre Users.tsx e os modais
 * de criar/editar usuário e gerenciar 2FA (extraído na fase 24, O3.5 da refatoração).
 */

export interface AppUser {
  id: number;
  username: string;
  displayName: string;
  extension: number;
  isActive: boolean;
  role: string;
  createdAt: string;
  businessUnitIds: number[];
  accessExpiresAt: string | null;
  accessIndeterminate: boolean;
  totpEnabled: boolean;
}

export interface BusinessUnitOption {
  id: number;
  name: string;
}

export interface CreateForm {
  username: string;
  password: string;
  displayName: string;
  role: string;
  businessUnitIds: number[];
  accessExpiresAt: string;
  accessIndeterminate: boolean;
}

export interface EditForm {
  displayName: string;
  password: string;
  isActive: boolean;
  role: string;
  businessUnitIds: number[];
  accessExpiresAt: string;
  accessIndeterminate: boolean;
}

export interface TotpSetup {
  secret: string;
  qrCodeUrl: string;
  issuer: string;
  account: string;
}

// Regra de negócio: acesso com prazo determinado nunca passa de 60 dias (espelha o backend).
export const MAX_ACCESS_DAYS = 60;
export const maxAccessDate = () => {
  const d = new Date();
  d.setDate(d.getDate() + MAX_ACCESS_DAYS);
  return d.toISOString().slice(0, 10);
};

export const toggleBu = (ids: number[], id: number): number[] =>
  ids.includes(id) ? ids.filter(x => x !== id) : [...ids, id];

export const EMPTY_CREATE: CreateForm = {
  username: '', password: '', displayName: '', role: 'USER',
  businessUnitIds: [], accessExpiresAt: maxAccessDate(), accessIndeterminate: false,
};
