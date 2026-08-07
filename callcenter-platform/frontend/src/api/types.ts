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

// Estado/interação do agente (Fase 4) — GET/POST /api/v1/callcenter/agent-state,
// GET /api/v1/callcenter/interactions/**
export type AgentState = 'DISPONIVEL' | 'EM_ATENDIMENTO' | 'ACW' | 'PAUSA' | 'OFFLINE';

export interface CcPauseReason {
  id: number;
  code: string;
  label: string;
  productive: boolean;
  active: boolean;
}

export interface AgentStateView {
  agentId: number;
  state: AgentState;
  pauseReasonLabel?: string;
  startedAt?: string;
}

export interface AgentStateRequest {
  state: AgentState;
  pauseReasonId?: number;
}

export interface CcDisposition {
  id: number;
  code: string;
  label: string;
  active: boolean;
}

export interface InteractionView {
  id: number;
  queueName?: string;
  ani?: string;
  queuedAt?: string;
  answeredAt?: string;
  endedAt?: string;
  dispositionLabel?: string;
}

// Painel de supervisão (Fase 6) — GET /api/v1/callcenter/supervision/snapshot
export interface QueueSupervisionView {
  queueId: number;
  queueName: string;
  displayName: string;
  waitingCount: number;
  longestWaitSeconds?: number;
  answeredToday: number;
  abandonedToday: number;
  serviceLevelPercent?: number;
}

export interface AgentSupervisionView {
  agentId: number;
  agentName: string;
  extension?: string;
  state?: AgentState;
  pauseReasonLabel?: string;
  secondsInState?: number;
  answeredToday: number;
}

export interface SupervisionSnapshot {
  queues: QueueSupervisionView[];
  agents: AgentSupervisionView[];
}

// GET/PUT /api/v1/callcenter/supervision/alert-config/{queueId}
export interface QueueAlertConfigView {
  queueId: number;
  maxWaitingCount?: number;
  minServiceLevelPercent?: number;
  enabled: boolean;
  lastNotifiedDate?: string;
}

export interface QueueAlertConfigRequest {
  maxWaitingCount?: number | null;
  minServiceLevelPercent?: number | null;
  enabled: boolean;
}

// ---- Call Center — Fluxos / Flow Builder (Fase 5a) — GET/POST/PUT/DELETE /api/v1/callcenter/fluxos ----
export type FlowChannel = 'voice' | 'chat' | 'both';
export type FlowVersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export interface FlowView {
  id: number;
  name: string;
  description?: string | null;
  channel: FlowChannel;
  entryExtension?: string | null;
  businessUnitId?: number | null;
  active: boolean;
  publishedVersionId?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface FlowVersionView {
  id: number;
  flowId: number;
  versionNumber: number;
  status: FlowVersionStatus;
  graph?: string | null;
  notes?: string | null;
  publishedAt?: string | null;
  publishedBy?: string | null;
  createdAt: string;
}

export interface FlowRequest {
  name: string;
  description?: string;
  channel: FlowChannel;
  entryExtension?: string;
  businessUnitId?: number;
}

export interface DraftSaveRequest {
  graph: string;
}

export interface FlowGraphValidationIssue {
  nodeId: string | null;
  message: string;
}

export interface FlowGraphValidationResult {
  errors: FlowGraphValidationIssue[];
  warnings: FlowGraphValidationIssue[];
}

// Catálogo de tipos de nó — fonte única servida pelo backend (GET .../fluxos/catalogo),
// nunca duplicado em código: nesta sub-fase todo nó vem com implementado=false (o motor
// de execução ARI/Stasis só chega na Fase 5b).
export interface FlowGraphNodeProperty {
  name: string;
  label: string;
  type: string;
}

export interface FlowGraphNodeType {
  type: string;
  label: string;
  channel: FlowChannel;
  implementado: boolean;
  properties: FlowGraphNodeProperty[];
}

// Grafo persistido no campo `graph` (JSON.stringify) — formato nativo do React Flow.
export interface FlowGraphDocument {
  schemaVersion: number;
  nodes: FlowGraphNodeInstance[];
  edges: FlowGraphEdge[];
}

export interface FlowGraphNodeInstance {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: { nodeType: string; label: string; properties: Record<string, string | number | boolean> };
}

export interface FlowGraphEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}
