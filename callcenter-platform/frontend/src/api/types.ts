/**
 * types.ts — subset de tipos TypeScript da API AsteriskIA usado pela SPA de Call Center.
 * Espelha os DTOs/entidades do backend (com.asteriskia.domain.callcenter) — mantido em
 * sincronia manual (mesmo padrão de duplicação já aceito entre backend/frontend do RBAC
 * granular e entre as SPAs Vite independentes).
 */

// ---- Auth ----
export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  type: string;
  expiresInHours: number;
  firstLoginCompleted?: boolean;
}

// ---- Business Unit (GET /api/v1/business-units) ----
export interface BusinessUnit {
  id: number;
  name: string;
  description?: string;
  isActive: boolean;
}

// ---- Call Center — Agentes (GET/POST/PUT/DELETE /api/v1/callcenter/agentes) ----
export interface CcExtension {
  id: number;
  extension: string;
  createdAt?: string;
}

export interface CcAgent {
  id: number;
  userId?: number;
  name: string;
  businessUnit?: BusinessUnit;
  active: boolean;
  extension?: CcExtension;
  createdAt?: string;
  updatedAt?: string;
}

/** Payload de criação/atualização de agente — espelha AgentRequest.java. */
export interface AgentRequest {
  name: string;
  userId?: number | null;
  businessUnitId?: number | null;
  extension: string;
}

// ---- Call Center — Filas (GET/POST/PUT/DELETE /api/v1/callcenter/filas) ----
export interface CcQueue {
  id: number;
  name: string;
  displayName: string;
  businessUnit?: BusinessUnit;
  strategy: string;
  timeoutSeconds: number;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/** Payload de criação/atualização de fila — espelha QueueRequest.java. */
export interface QueueRequest {
  name: string;
  displayName: string;
  businessUnitId?: number | null;
  strategy?: string;
  timeoutSeconds?: number;
}

export interface CcQueueMember {
  id: number;
  queue: CcQueue;
  agent: CcAgent;
  penalty: number;
  createdAt?: string;
}

// ---- Call Center — Skills (GET/POST/PUT/DELETE /api/v1/callcenter/skills) ----
export interface CcSkill {
  id: number;
  name: string;
  description?: string;
}

export interface SkillRequest {
  name: string;
  description?: string;
}
