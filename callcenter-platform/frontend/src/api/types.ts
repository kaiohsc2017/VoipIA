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
  /** Fase 3 — aviso de gravação (LGPD): grava por padrão se true. */
  recordingEnabled: boolean;
  /** Fase 3 — caminho do áudio de consentimento tocado antes de MixMonitor (null = sem aviso). */
  consentMessagePath?: string | null;
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
  recordingEnabled?: boolean | null;
  consentMessagePath?: string | null;
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

// ---- Call Center — Gravações (Fase 3) ----
// GET /api/v1/callcenter/recordings (paginado) / GET /api/v1/callcenter/recordings/{id}/audio
export interface CcRecording {
  id: number;
  queue?: CcQueue;
  queueExtension: string;
  channelUniqueId: string;
  filePath: string;
  businessUnit?: BusinessUnit;
  consentPlayed: boolean;
  startedAt: string;
  endedAt?: string;
  durationSeconds?: number;
  createdAt?: string;
}

/** Página no formato padrão do Spring Data (Page<T>). */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// GET/PUT /api/v1/callcenter/recordings/retention-config
export interface RetentionConfig {
  retentionDays: number;
  lastPurgeAt?: string;
  lastPurgeDeletedCount?: number;
}

export interface RetentionConfigRequest {
  retentionDays: number;
}

export interface RetentionRunResult {
  deletedCount: number;
}

// GET/PUT /api/v1/callcenter/recordings/disk-alert-config
export interface DiskAlertConfig {
  thresholdPercent: number;
  enabled: boolean;
  lastNotifiedDate?: string;
  currentUsagePercent?: number;
}

export interface DiskAlertConfigRequest {
  thresholdPercent: number;
  enabled: boolean;
}
