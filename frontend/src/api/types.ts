/**
 * types.ts — TypeScript types para a API VoipIA
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

// ---- Grupos de acesso (RBAC granular — V22) ----
export interface AccessGroupPermission {
  resourceKey: string;
  canRead: boolean;
  canWrite: boolean;
}

export interface AccessGroup {
  id: number;
  name: string;
  description: string | null;
  isSystem: boolean;
  permissions: AccessGroupPermission[];
}

export interface AccessGroupRequest {
  name: string;
  description: string | null;
  permissions: AccessGroupPermission[];
}

// ---- URAs (Módulo 1) ----
export interface Ura {
  id: number;
  name: string;
  extension: string;
  active: boolean;
  jiraIntegrationEnabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface UraAnswerView {
  questionId: number;
  questionText: string;
  value: string;
}

// ---- Call Records (Módulo 1) ----
export interface CallRecord {
  id: number;
  uraId: number;
  callUuid: string;
  callDate: string;
  callDurationSecs: number;
  callerNumber: string;
  clientName?: string;
  transcription?: string;
  jiraIssueKey?: string;
  jiraIssueStatus?: string;
  audioFilePath?: string;
  callType?: string;
  reportedRamal?: string;
  priority?: string;
  answers?: UraAnswerView[];
  createdAt: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// ---- Custos de IA (Módulo 1 — aba Custos IA / Dashboard de Custos) ----
export interface CallCostView {
  id: number;
  callDate: string;
  clientName?: string;
  uraId: number;
  callDurationSecs: number;
  sttTokensIn: number;
  sttTokensOut: number;
  sttModel?: string;
  llmTokensIn: number;
  llmTokensOut: number;
  llmModel?: string;
  ttsTokensIn: number;
  ttsTokensOut: number;
  ttsModel?: string;
  totalTokens: number;
  estimatedCostUsd: number;
}

export interface MonthlyCostSummary {
  month: string; // "yyyy-MM"
  sttCostUsd: number;
  llmCostUsd: number;
  ttsCostUsd: number;
  totalCostUsd: number;
  callCount: number;
}

export interface AiModelPricing {
  modelId: string;
  provider: string;
  pricePerMillionInputUsd: number;
  pricePerMillionOutputUsd: number;
  updatedAt?: string;
  updatedBy?: string;
}

// Resultado da busca automática/manual de preço (POST /ai/model-pricing/sync-now)
export interface PricingFetchResult {
  modelId: string;
  success: boolean;
  pricePerMillionInputUsd: number | null;
  pricePerMillionOutputUsd: number | null;
  failureReason: string | null;
}

// ---- URA Questions (Módulo 1) ----
export interface UraQuestion {
  id: number;
  uraId: number;
  questionOrder: number;
  questionText: string;
  jiraFieldKey: string;
  expectedValues?: string;
  isActive: boolean;
}

// ---- Master Data (Módulo 2) ----
export interface BusinessUnit {
  id: number;
  name: string;
  description?: string;
  isActive: boolean;
}

export interface Segment {
  id: number;
  name: string;
  description?: string;
  isActive: boolean;
}

export interface Client {
  id: number;
  name: string;
  document?: string;
  description?: string;
  isActive: boolean;
}

export interface Operation {
  id: number;
  name: string;
  description?: string;
  isActive: boolean;
}

// ---- Cadastros: Operadoras, 0800 e Linhas ----
export interface Operadora {
  id: number;
  nome: string;
  isActive: boolean;
}

export interface Numero0800Regenerado {
  id?: number;
  ordem: number;
  numeroRegenerado?: string;
  vdn?: string;
  vetor?: string;
  operadora?: { id: number; nome?: string } | null;
}

export interface Numero0800 {
  id: number;
  operadora: { id: number; nome?: string };
  numero: string;
  client?: { id: number; name?: string } | null;
  observacao?: string;
  isActive: boolean;
  businessUnits: BusinessUnit[];
  regenerados: Numero0800Regenerado[];
}

export interface Linha {
  id: number;
  operadora: { id: number; nome?: string };
  operation?: { id: number; name?: string } | null;
  chave?: string;
  ipOperadora?: string;
  ipAutoglass?: string;
  observacao?: string;
  isActive: boolean;
  businessUnits: BusinessUnit[];
}

// ---- Number Tests (Módulo 2) ----
export interface NumberTest {
  id: number;
  phoneNumber: string;
  businessUnit: BusinessUnit;
  client: Client;
  operation: Operation;
  segment: Segment;
  startTime: string;
  intervalMinutes: number;
  quantity: number;
  isActive: boolean;
  createdAt: string;
}

export interface NumberTestCreate {
  phoneNumber: string;
  businessUnit: { id: number };
  client: { id: number };
  operation: { id: number };
  segment: { id: number };
  startTime: string;
  intervalMinutes: number;
  quantity: number;
  isActive: boolean;
}

export interface TestResult {
  id: number;
  numberTest: {
    id: number;
    phoneNumber: string;
    businessUnit: BusinessUnit;
    client: Client;
    operation: Operation;
    segment: Segment;
    startTime: string;
    intervalMinutes: number;
    quantity: number;
    isActive: boolean;
  };
  executedAt: string;
  sipResponseCode?: number;
  sipResponseReason?: string;
  status: string;
  executionOrder: number;
  nextScheduledAt?: string;
  asteriskCallId?: string;
}

// ---- Alerts (Módulo 3) ----
export interface AlertCall {
  id: number;
  callDate: string;
  phoneNumber: string;
  callStatus: string;
  sipResponseCode?: number;
  sipResponseReason?: string;
  callDurationSecs: number;
  zabbixTriggerId: string;
  zabbixIncidentSummary: string;
  zabbixSeverity?: string;
  zabbixHost?: string;
  audioFilePath?: string;
  telegramMessageContent?: string;
  telegramSentAt?: string;
  asteriskCallId?: string;
}

export interface AlertContact {
  id: number;
  name: string;
  phoneNumber: string;
  isActive: boolean;
  priorityOrder: number;
  operationId?: number;
}

// ---- Insights (transcrição/análise de IA de gravações do call center Verint) ----
// Módulo apartado do domínio Asterisk — dados vêm de /opt/audio (Verint), não de call_records.
export interface InsightsListItem {
  id: number;
  callRef: string;
  callStarttime?: string;
  durationSeconds?: number;
  agentName?: string;
  direction?: 'inbound' | 'outbound';
  skill?: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  categoriaAssunto?: string;
  sentimentoGeral?: string;
  criticidade?: 'baixa' | 'media' | 'alta' | 'urgente';
}

export interface CallAudioFile {
  id: number;
  callRef: string;
  wavPath: string;
  xmlPath: string;
  durationSeconds?: number;
  callStarttime?: string;
  agentName?: string;
  agentIdVerint?: string;
  extension?: string;
  ani?: string;
  dnis?: string;
  direction?: 'inbound' | 'outbound';
  skill?: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  errorMsg?: string;
}

export interface CallTranscriptSegment {
  id: number;
  speaker: 'agente' | 'cliente' | 'indefinido';
  startMs: number;
  endMs: number;
  text: string;
  toneAcoustic?: string;
  toneSemantic?: string;
}

export interface CallInsightFinding {
  id: number;
  tipo: 'melhoria' | 'falha' | 'treinamento' | 'tendencia';
  descricao: string;
  trechoReferencia?: string;
  prioridade: 'baixa' | 'media' | 'alta';
}

export interface CallInsight {
  id: number;
  resumo?: string;
  categoriaAssunto?: string;
  sentimentoGeral?: string;
  aderenciaScript?: number;
  criticidade: 'baixa' | 'media' | 'alta' | 'urgente';
}

export interface InsightsDetailResponse {
  audioFile: CallAudioFile;
  segments: CallTranscriptSegment[];
  insights: CallInsight | null;
  findings: CallInsightFinding[];
}

export interface InsightsDashboardSummary {
  totalChamadas: number;
  porCriticidade: Record<string, number>;
  porCategoria: Record<string, number>;
  achadosPorTipo: Record<string, number>;
}

// ---- Insights — aba "Custos IA" / "Dashboard de Custos" (mirror de CallCostView/MonthlyCostSummary) ----
export interface InsightCostView {
  id: number;
  callRef: string;
  callStarttime?: string;
  agentName?: string;
  durationSeconds?: number;
  sttTokensIn: number;
  sttTokensOut: number;
  sttModel?: string;
  llmTokensIn: number;
  llmTokensOut: number;
  llmModel?: string;
  totalTokens: number;
  estimatedCostUsd: number;
}

export interface InsightMonthlyCostSummary {
  month: string; // "yyyy-MM"
  sttCostUsd: number;
  llmCostUsd: number;
  totalCostUsd: number;
  callCount: number;
}

// ---- Insights — aba "Processamento" (status/fila de arquivos descobertos em /opt/audio) ----
export interface InsightProcessingItem {
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

// ---- Financeiro — drill-down entre lista e dashboard de custos (mirror reduzido de
// RankingDrillDownFilters/InsightsDrillDownFilters, só os campos usados pelas telas
// de custo: dateFrom/dateTo do mês clicado, id da linha clicada) ----
export interface FinanceiroDrillDownFilters {
  id?: number;
  dateFrom?: string;
  dateTo?: string;
}

// ---- Financeiro — alerta de gasto de IA por frente (ver CostAlertScheduler no backend) ----
export interface CostAlertConfigView {
  scope: string;
  thresholdUsd: number;
  enabled: boolean;
  lastNotifiedMonth: string | null;
  currentMonthSpendUsd: number;
}

// ---- Dashboard KPIs ----
export interface DashboardStats {
  callsToday: number;
  testsToday: number;
  testSuccessRate: number;
  activeAlerts: number;
  recentResults: TestResult[];
}
