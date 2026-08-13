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
  /** Fase 12.5 — só considerado na criação; clona os membros (agente+prioridade) da fila de origem. */
  copyMembersFromQueueId?: number | null;
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

// ---- Call Center — Motivos de pausa (Fase 12.6, GET/POST/PUT/DELETE /callcenter/pause-reasons) ----
export interface CcPauseReason {
  id: number;
  code: string;
  label: string;
  productive: boolean;
  active: boolean;
}

export interface PauseReasonRequest {
  code: string;
  label: string;
  productive?: boolean;
  active?: boolean;
}

// ---- Call Center — Tabulações (Fase 12.6, GET/POST/PUT/DELETE /callcenter/dispositions) ----
export interface CcDisposition {
  id: number;
  code: string;
  label: string;
  active: boolean;
}

export interface DispositionRequest {
  code: string;
  label: string;
  active?: boolean;
}

// ---- Call Center — filas de um agente / prioridade (Fase 12.3/12.4) ----
export interface QueueMemberBody {
  penalty?: number;
}

// ---- Usuário do Telecom (GET /users) — só os campos usados no seletor de "usuário
// vinculado" do agente (Fase 12.4). ADMIN sempre lê; grupo customizado sem telecom.users
// recebe 403 e o formulário cai para digitação manual do id. ----
export interface AppUserOption {
  id: number;
  username: string;
  displayName: string;
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
  // Fase 23 — INBOUND (fila) ou OUTBOUND (ativo manual do agente, sem fila).
  direction: 'INBOUND' | 'OUTBOUND';
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

// ---- Insights do Call Center (Fase 8) — GET /api/v1/callcenter/insights/** ----
// Mesmo pipeline de IA do módulo Insights (Verint), aplicado às gravações source=callcenter.
// Campos exclusivos do XML da Verint (customerNumber, organization, dnis, codec, transferEvents…)
// não existem aqui — o Call Center não descobre por XML, então esses valores nunca são
// preenchidos; por isso o tipo abaixo é um subconjunto de InsightsListItem/CallAudioFile,
// não uma cópia integral.
export interface CcInsightsListItem {
  id: number;
  callRef: string;
  callStarttime?: string;
  durationSeconds?: number;
  agentName?: string;
  skill?: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  categoriaAssunto?: string;
  sentimentoGeral?: string;
  criticidade?: 'baixa' | 'media' | 'alta' | 'urgente';
  notaTotal?: number;
  isFailed?: boolean;
  ani?: string;
}

export interface CcInsightsAudioFile {
  id: number;
  callRef: string;
  durationSeconds?: number;
  callStarttime?: string;
  agentName?: string;
  ani?: string;
  skill?: string;
  status: 'pending' | 'processing' | 'done' | 'error';
}

export interface CcInsightsSegment {
  id: number;
  speaker: 'agente' | 'cliente' | 'indefinido';
  startMs: number;
  endMs: number;
  text: string;
  toneAcoustic?: string;
  toneSemantic?: string;
}

export interface CcInsightFinding {
  id: number;
  tipo: 'melhoria' | 'falha' | 'treinamento' | 'tendencia';
  descricao: string;
  trechoReferencia?: string;
  prioridade: 'baixa' | 'media' | 'alta';
}

export interface CcInsight {
  id: number;
  resumo?: string;
  categoriaAssunto?: string;
  sentimentoGeral?: string;
  aderenciaScript?: number;
  criticidade: 'baixa' | 'media' | 'alta' | 'urgente';
}

export interface CcEvaluationItem {
  id: number;
  itemId: number;
  nota: number;
  justificativa?: string;
  trechoReferencia?: string;
}

export interface CcEvaluation {
  id: number;
  audioFileId: number;
  scorecardId: number;
  notaTotal: number;
  isFailed: boolean;
  failReason?: string;
}

export interface CcInsightsDetailResponse {
  audioFile: CcInsightsAudioFile;
  segments: CcInsightsSegment[];
  insights: CcInsight | null;
  findings: CcInsightFinding[];
  evaluation: CcEvaluation | null;
  evaluationItems: CcEvaluationItem[];
}

export interface CcInsightsDashboardSummary {
  totalChamadas: number;
  porCriticidade: Record<string, number>;
  porCategoria: Record<string, number>;
  achadosPorTipo: Record<string, number>;
  mediaNotaGeral: number;
  agentesAbaixoMedia: number;
  autoFailsNoPeriodo: number;
}

export interface CcInsightsDrillDownFilters {
  id?: number;
  categoria?: string;
  criticidade?: string;
  findingType?: string;
  isFailed?: boolean;
}

export interface CcInsightProcessingItem {
  id: number;
  callRef: string;
  fileName: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  ingestedAt?: string;
  startedAt?: string;
  processedAt?: string;
  errorMsg?: string;
  queuePosition?: number;
}

// ---- Insights do Call Center — aba "Fichas de Qualidade" (Fase 8, somente leitura —
// a configuração da ficha é global, reusa GET /insights/scorecards) ----
export interface CcScorecardItemDto {
  id?: number;
  ordem: number;
  pergunta: string;
  peso: number;
  notaMaxima: number;
  isCritical: boolean;
}

export interface CcScorecardDto {
  id: number;
  name: string;
  description?: string;
  isActive: boolean;
  version: number;
  items: CcScorecardItemDto[];
  createdAt?: string;
  updatedAt?: string;
}

// ---- Insights do Call Center — aba "Relatórios" (Fase 8, espelha /insights/reports
// com source='callcenter', V55) ----
export interface CcAgentReportItemAverage {
  itemId: number;
  pergunta: string;
  media: number;
}

export interface CcAgentReportFinding {
  tipo: string;
  descricao: string;
  trechoReferencia?: string;
  prioridade: string;
}

export interface CcAgentReportNarrative {
  pontosFortes: string[];
  pontosMelhoria: string[];
  recomendacoes: string[];
  comparacaoTextual?: string;
}

export interface CcAgentReportAggregate {
  totalChamadas: number;
  notaMedia?: number;
  autoFails: number;
  notaPorItem: CcAgentReportItemAverage[];
  achadosPorTipo: Record<string, number>;
}

export interface CcAgentReportContent {
  aggregate: CcAgentReportAggregate;
  achadosGraves: CcAgentReportFinding[];
  narrative: CcAgentReportNarrative | null;
}

export interface CcAgentReportItemDelta {
  itemId: number;
  pergunta: string;
  anterior?: number;
  atual?: number;
  delta?: number;
}

export interface CcAgentReportEvolution {
  previousReportId: number;
  partial: boolean;
  deltaNotaMedia?: number;
  deltaPorItem: CcAgentReportItemDelta[];
}

export interface CcAgentReportDto {
  id: number;
  agentName: string;
  dateFrom: string;
  dateTo: string;
  requestedBy: string;
  requestedAt: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  errorMsg?: string;
  content: CcAgentReportContent | null;
  previousReportId?: number;
  evolution: CcAgentReportEvolution | null;
  completedAt?: string;
}

export interface CcAgentEvolutionSnapshot {
  id: number;
  agentName: string;
  reportId: number;
  itemId?: number;
  metricKey: string;
  valor: number;
  createdAt: string;
}

// ---- Canal de Chat — Fase 7a (base interna, sem widget público ainda) ----
export interface CcChatChannel {
  id: number;
  code: string;
  displayName: string;
  active: boolean;
}

export interface CcChatSession {
  id: number;
  channel: CcChatChannel;
  queue: CcQueue;
  customerRef: string;
  customerName?: string;
  status: 'waiting' | 'active' | 'closed';
  assignedAgent?: CcAgent;
  disposition?: CcDisposition;
  startedAt: string;
  claimedAt?: string;
  closedAt?: string;
}

export interface CcChatMessage {
  id: number;
  sessionId: number;
  senderType: 'customer' | 'agent' | 'system';
  senderName?: string;
  body: string;
  createdAt: string;
}

export interface CcCannedResponse {
  id: number;
  title: string;
  body: string;
  category?: string;
  active: boolean;
}

// ---- Relatórios analíticos — Fase 9a (só fila de voz nesta fatia) ----
export type ReportGranularity = 'day' | 'week' | 'month' | 'year';

export interface QueuePeriodMetrics {
  queueId: number | null;
  queueName: string | null;
  periodLabel: string;
  received: number;
  answered: number;
  abandoned: number;
  abandonRatePct: number | null;
  avgWaitSeconds: number | null;
  avgTalkSeconds: number | null;
  serviceLevelPct: number | null;
}

export interface QueuePeriodComparison {
  periodA: QueuePeriodMetrics;
  periodB: QueuePeriodMetrics;
  receivedDelta: number;
  answeredDelta: number;
  abandonedDelta: number;
  abandonRatePctDelta: number | null;
  avgWaitSecondsDelta: number | null;
  avgTalkSecondsDelta: number | null;
  serviceLevelPctDelta: number | null;
}

export interface AgentPeriodMetrics {
  agentId: number | null;
  agentName: string | null;
  periodLabel: string;
  answered: number;
  avgTalkSeconds: number | null;
  occupiedSeconds: number;
  availableSeconds: number;
  pausedSeconds: number;
  offlineSeconds: number;
  occupancyPct: number | null;
}

export interface AgentPeriodComparison {
  periodA: AgentPeriodMetrics;
  periodB: AgentPeriodMetrics;
  answeredDelta: number;
  avgTalkSecondsDelta: number | null;
  occupiedSecondsDelta: number;
  availableSecondsDelta: number;
  occupancyPctDelta: number | null;
}

// Fase 19 (Parte III) — ranges de ramal configuráveis + interruptor global de NPS.
export type CcRangeType = 'AGENT' | 'QUEUE' | 'FLOW';

export interface CcRangeView {
  type: CcRangeType;
  label: string;
  start: number;
  end: number;
}

export interface CcSettingsView {
  agentRange: CcRangeView;
  queueRange: CcRangeView;
  flowRange: CcRangeView;
  npsEnabledGlobally: boolean;
}

export interface CcUpdateRangeResult {
  range: CcRangeView;
  extensionsOutsideRange: number;
}
