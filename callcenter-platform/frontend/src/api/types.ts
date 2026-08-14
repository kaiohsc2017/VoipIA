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
  /** Fase 21 — pesquisa de satisfação desta fila (null = sem pesquisa). O interruptor global
   * (aba Configurações) sobrepõe mesmo com pesquisa configurada. */
  survey?: SurveySummary | null;
  npsAlertEnabled: boolean;
  npsAlertThreshold?: number | null;
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
  surveyId?: number | null;
  npsAlertEnabled?: boolean | null;
  npsAlertThreshold?: number | null;
}

// Pesquisa de satisfação (NPS) — Fase 21. Espelha SurveyDto.java.
export type SurveyMode = 'DTMF_SIMPLES' | 'DTMF_MULTI' | 'FALADA_IA' | 'DTMF_COMENTARIO';

export interface SurveySummary {
  id: number;
  name: string;
  mode: SurveyMode;
  scaleMax: number;
  active: boolean;
}

export interface SurveyQuestion {
  id?: number;
  orderIndex: number;
  text: string;
  audioPath?: string | null;
}

export interface Survey extends SurveySummary {
  businessUnitId?: number | null;
  questions: SurveyQuestion[];
  createdAt?: string;
  updatedAt?: string;
}

export interface SurveyRequest {
  name: string;
  mode: SurveyMode;
  scaleMax: number;
  businessUnitId?: number | null;
  questions: SurveyQuestion[];
}

// Financeiro — alerta de gasto de IA da frente callcenter_nps (Fase 21, §21.5).
export interface CostAlertConfigView {
  scope: string;
  thresholdUsd: number;
  enabled: boolean;
  lastNotifiedMonth: string | null;
  currentMonthSpendUsd: number;
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

// ---- Call Center — Roteamento por skill (Fase 5f.1) ----
// Escala de nível 1-5 (1=iniciante ... 5=especialista), ver V76. Skill decide só elegibilidade
// de participação em fila — nunca a prioridade manual (penalty), que continua 100% manual.
export interface CcAgentSkill {
  agent: CcAgent;
  skill: CcSkill;
  level: number;
}

export interface CcQueueSkill {
  queue: CcQueue;
  skill: CcSkill;
  minLevel: number;
}

/** Resultado do recálculo explícito de participação por skill (POST .../recalcular-skills). */
export interface SkillRecalculationResult {
  added: number;
  removed: number;
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

// ---- Call Center — Horário de funcionamento (Fase 5e.1, GET/POST/PUT/DELETE /callcenter/business-hours) ----
export interface CcBusinessHoursSlot {
  id: number;
  dayOfWeek: number; // 1=segunda .. 7=domingo (java.time.DayOfWeek.getValue())
  startTime: string; // "HH:mm:ss"
  endTime: string;
}

export interface CcBusinessHoursCalendar {
  id: number;
  name: string;
  timezone: string;
  active: boolean;
  createdAt: string;
  slots: CcBusinessHoursSlot[];
}

export interface BusinessHoursCalendarRequest {
  name: string;
  timezone: string;
  active: boolean;
}

export interface BusinessHoursSlotRequest {
  dayOfWeek: number;
  startTime: string;
  endTime: string;
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

/** Sessão de co-browsing gravado do chat (Fase 17). */
export interface CcCobrowseSession {
  id: number;
  chatSessionId: number;
  businessUnitId?: number;
  consentStatus: 'pending' | 'granted' | 'denied' | 'revoked';
  consentAt?: string;
  revokedAt?: string;
  filePath?: string;
  sizeBytes: number;
  eventCount: number;
  truncated: boolean;
  startedAt: string;
  lastEventAt?: string;
  purgedAt?: string;
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

// GET/PUT /api/v1/callcenter/cobrowsing/retention-config (Fase 17d)
export interface CobrowseRetentionConfig {
  retentionDays: number;
  lastPurgeAt?: string;
  lastPurgeDeletedCount?: number;
}

export interface CobrowseRetentionConfigRequest {
  retentionDays: number;
}

export interface CobrowseRetentionRunResult {
  purgedCount: number;
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

// Painel pessoal do agente (Fase 22) — GET /callcenter/desktop/me/*. Somente leitura do próprio
// dia; o backend nunca aceita agentId do chamador, sempre resolve pelo usuário autenticado.
export interface DesktopSummaryView {
  callsAnsweredToday: number;
  avgTalkSeconds: number | null;
  loggedSeconds: number;
  pauseSeconds: number;
}

// D21: EM_PROCESSAMENTO nunca tem ação associada nesta tela — é só um estado informativo. O
// disparo de processamento é exclusivo das telas de Processamento do Insights.
export type DesktopTranscriptionStatus = 'SEM_GRAVACAO' | 'EM_PROCESSAMENTO' | 'DISPONIVEL';

export interface DesktopCallHistoryItem {
  interactionId: number;
  dateTime: string;
  direction: 'INBOUND' | 'OUTBOUND';
  ani?: string;
  queueName?: string;
  talkSeconds?: number;
  npsScore?: number;
  recordingUrl?: string;
  transcriptionStatus: DesktopTranscriptionStatus;
  transcript?: string;
}

export interface DesktopPauseItem {
  reasonLabel: string;
  startedAt: string;
  endedAt?: string;
  durationSeconds: number;
}

// Painel de supervisão (Fase 6) — GET /api/v1/callcenter/supervision/snapshot
// Fase 15.1 — chamador em espera ao vivo (AMI QueueStatus).
export interface WaitingCallerView {
  position?: number;
  ani?: string;
  waitSeconds?: number;
  channelUniqueId: string;
  channelName?: string;
}

export interface QueueSupervisionView {
  queueId: number;
  queueName: string;
  displayName: string;
  waitingCount: number;
  longestWaitSeconds?: number;
  answeredToday: number;
  abandonedToday: number;
  serviceLevelPercent?: number;
  waitingCallers: WaitingCallerView[];
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

// Simulador de fluxo — POST /callcenter/fluxos/{id}/simulate (Fase 5d, dry-run: nunca persiste
// execução real nem chama IA — ver FlowSimulationService no backend).
export interface FlowSimulationRequest {
  variaveis: Record<string, string>;
  respostasSimuladas: string[];
}

export interface FlowSimulationStepView {
  nodeId: string;
  nodeType: string | null;
  label: string | null;
  detail: string | null;
  takenEdgeId: string | null;
}

export interface FlowSimulationResult {
  flowId: number;
  flowVersionId: number;
  versionStatus: FlowVersionStatus;
  outcome: string;
  steps: FlowSimulationStepView[];
  finalVariables: Record<string, string>;
}

// Catálogo de tipos de nó — fonte única servida pelo backend (GET .../fluxos/catalogo),
// nunca duplicado em código: nesta sub-fase todo nó vem com implementado=false (o motor
// de execução ARI/Stasis só chega na Fase 5b).
export interface FlowGraphNodePropertyOption {
  value: string;
  label: string;
}

export interface FlowGraphNodeProperty {
  name: string;
  label: string;
  // 'audio'/'keypad' (Fase 5c) somam-se a string|number|boolean|select.
  type: string;
  options?: FlowGraphNodePropertyOption[];
  required?: boolean;
}

export interface FlowGraphNodeType {
  type: string;
  label: string;
  channel: FlowChannel;
  implementado: boolean;
  properties: FlowGraphNodeProperty[];
}

// Biblioteca de áudios do Flow Builder (Fase 5c) — sempre PCM 8kHz/16-bit mono.
export interface CcAudioFile {
  id: number;
  name: string;
  fileName: string;
  durationSeconds: number | null;
  businessUnitId: number | null;
  createdAt: string;
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

// ---- Canal de Chat — Fase 7a (base interna) + Fase 24 (fila padrão + fluxo de bot) ----
export interface CcChatChannel {
  id: number;
  code: string;
  displayName: string;
  active: boolean;
}

/** DTO de leitura de GET /callcenter/chat/channels (Fase 24) — distinto de {@link CcChatChannel}
 * (referência embutida em {@link CcChatSession}), que continua o shape mínimo herdado da 7a. */
export interface ChatChannelView {
  id: number;
  code: string;
  displayName: string;
  type: string;
  defaultQueueId?: number;
  defaultQueueName?: string;
  botFlowId?: number;
  botFlowName?: string;
  greetingMessage?: string;
  awayMessage?: string;
  active: boolean;
}

export interface ChatChannelRequest {
  code: string;
  displayName: string;
  type?: string;
  defaultQueueId?: number | null;
  botFlowId?: number | null;
  greetingMessage?: string | null;
  awayMessage?: string | null;
  active?: boolean;
}

export interface CcChatSession {
  id: number;
  channel: CcChatChannel;
  queue: CcQueue;
  customerRef: string;
  customerName?: string;
  status: 'bot' | 'waiting' | 'active' | 'closed';
  assignedAgent?: CcAgent;
  disposition?: CcDisposition;
  startedAt: string;
  claimedAt?: string;
  closedAt?: string;
}

export interface CcChatMessage {
  id: number;
  sessionId: number;
  senderType: 'customer' | 'agent' | 'system' | 'bot';
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

// ─── Fase 9c — relatório analítico de chamada e chat ───────────────────────────────────────────

export interface CallReportRow {
  interactionId: number;
  queuedAt: string;
  answeredAt: string | null;
  endedAt: string | null;
  direction: 'INBOUND' | 'OUTBOUND' | null;
  ani: string | null;
  queueName: string | null;
  agentName: string | null;
  waitSeconds: number | null;
  npsScore: number | null;
  flowName: string | null;
  chosenOptionDigit: string | null;
  chosenOptionLabel: string | null;
  audioFileId: number | null;
  categoriaAssunto: string | null;
  sentimentoGeral: string | null;
  criticidade: string | null;
  findingsByTipo: Record<string, number>;
}

export interface ChatReportRow {
  sessionId: number;
  startedAt: string;
  claimedAt: string | null;
  closedAt: string | null;
  customerRef: string;
  customerName: string | null;
  queueName: string | null;
  agentName: string | null;
  dispositionName: string | null;
  transcriptPath: string | null;
}

// ─── Fase 26 — relatório de qualidade ───────────────────────────────────────────────────────────

export type QualityReportScopeType = 'AGENT' | 'QUEUE' | 'GERAL';

export interface CcQualityReportItemAverage {
  itemId: number;
  pergunta: string | null;
  media: number | null;
}

export interface CcQualityReportContent {
  notaMedia: number | null;
  totalAvaliacoes: number;
  totalReprovadas: number;
  notaPorItem: CcQualityReportItemAverage[];
}

export interface CcQualityReportItemDelta {
  itemId: number;
  pergunta: string | null;
  mediaAnterior: number | null;
  mediaAtual: number | null;
  delta: number | null;
}

export interface CcQualityReportEvolution {
  notaMediaAnterior: number | null;
  notaMediaDelta: number | null;
  itens: CcQualityReportItemDelta[];
}

export interface CcQualityReportDto {
  id: number;
  scopeType: QualityReportScopeType;
  scopeValue: string | null;
  dateFrom: string;
  dateTo: string;
  requestedBy: string;
  requestedAt: string;
  content: CcQualityReportContent;
  previousReportId: number | null;
  evolution: CcQualityReportEvolution | null;
}

export interface CcHoliday {
  id: number;
  holidayDate: string;
  description: string | null;
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

// ─── Base de conhecimento / RAG do chat (Fase 25) ───────────────────────────

export interface CcKbArticle {
  id: number;
  title: string;
  body: string;
  tags: string | null;
  active: boolean;
  version: number;
  indexedVersion: number;
  createdAt: string;
  updatedAt: string;
}

export interface CcKbArticleRequest {
  title: string;
  body: string;
  tags: string | null;
}

export interface CcKbExternalSource {
  id: number;
  url: string;
  active: boolean;
  lastFetchedAt: string | null;
  lastFetchSuccess: boolean | null;
  lastFetchError: string | null;
  createdAt: string;
}

export interface CcKbExternalSourceRequest {
  url: string;
}

export interface CcKbStatsView {
  matched: number;
  total: number;
  containmentRate: number;
}

// ─── Fase 27 — gamificação, perfil do cliente, produtividade ───────────────────────────────────

export interface AgentGamificationRow {
  position: number | null;
  agentId: number;
  agentName: string;
  totalAtendidas: number;
  totalRealizadas: number;
  npsMedio: number | null;
}

export interface GamificationReport {
  minCalls: number;
  ranking: AgentGamificationRow[];
  belowMinimum: AgentGamificationRow[];
}

export interface CustomerProfileSummaryRow {
  normalizedId: string;
  displayContact: string | null;
  totalChamadas: number;
  totalChats: number;
  primeiroContato: string | null;
  ultimoContato: string | null;
  npsMedio: number | null;
  topAssunto: string | null;
}

export interface CustomerProfileSubjectCount {
  assunto: string;
  total: number;
}

export interface CustomerProfileInteractionSummary {
  interactionId: number;
  queuedAt: string;
  queueName: string | null;
  agentName: string | null;
  npsScore: number | null;
  dispositionLabel: string | null;
  categoriaAssunto: string | null;
}

export interface CustomerProfileChatSummary {
  sessionId: number;
  startedAt: string;
  queueName: string | null;
  agentName: string | null;
  dispositionLabel: string | null;
}

export interface CustomerProfileDetail {
  normalizedId: string;
  displayContact: string | null;
  totalChamadas: number;
  totalChats: number;
  npsMedio: number | null;
  topAssuntos: CustomerProfileSubjectCount[];
  chamadas: CustomerProfileInteractionSummary[];
  chats: CustomerProfileChatSummary[];
}

export interface AgentProductivityItemAverage {
  itemId: number;
  pergunta: string | null;
  media: number | null;
}

export interface AgentProductivityAggregate {
  totalChamadas: number;
  notaMedia: number | null;
  autoFails: number;
  notaPorItem: AgentProductivityItemAverage[];
  achadosPorTipo: Record<string, number>;
}

export interface AgentProductivityFinding {
  tipo: string;
  descricao: string;
  trechoReferencia: string | null;
  prioridade: string;
}

export interface AgentProductivityResumo {
  totalAtendidas: number;
  totalRealizadas: number;
  avgTalkSeconds: number | null;
  avgOutboundTalkSeconds: number | null;
  npsMedio: number | null;
  occupancyPct: number | null;
  occupiedSeconds: number;
  availableSeconds: number;
  pausedSeconds: number;
  offlineSeconds: number;
}

export interface AgentProductivityStateEntry {
  state: string;
  pauseReasonLabel: string | null;
  startedAt: string;
  endedAt: string | null;
}

export interface AgentProductivityReport {
  agentId: number;
  agentName: string;
  resumo: AgentProductivityResumo;
  timeline: AgentProductivityStateEntry[];
  analise: AgentProductivityAggregate;
  achadosGraves: AgentProductivityFinding[];
  pontosFortes: string[];
  pontosMelhoria: string[];
}
