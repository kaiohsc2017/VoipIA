/**
 * types.ts — subset de tipos TypeScript da API VoipIA usado pela SPA de Insights.
 * Espelha frontend/src/api/types.ts — mantido em sincronia manual (mesmo padrão
 * de duplicação já aceito entre backend/frontend do RBAC granular).
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

// ---- Paginação (Spring Data Page<T>) ----
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
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
  notaTotal?: number;
  isFailed?: boolean;
  // ─── V43/V44 — colunas da tabela (plano insights-chamadas-campos-xml) ───
  // customerNumber saiu daqui (adendo, decisão 11) — só existe em CallAudioFile (detalhe).
  extension?: string;
  /** Login do agente no PBX (tag agentid/pbx_login_id) — adendo pós-deploy, decisão 11. */
  agentLoginId?: string;
  /** Já vem calculado do backend: para direction=outbound é o dnis bruto, não o
   * ani bruto (que seria o ramal do próprio atendente) — ver decisão 9 do plano.
   * Exibido na tabela como "Tel. Cliente" (decisão 11). */
  ani?: string;
  disconnectedBy?: 'atendente' | 'cliente';
  numberOfTransfers?: number;
  transferTargetExtension?: string;
  transferTargetAgentName?: string;
}

export interface CallAudioFile {
  id: number;
  callRef: string;
  durationSeconds?: number;
  callStarttime?: string;
  agentName?: string;
  agentIdVerint?: string;
  agentLoginId?: string;
  extension?: string;
  ani?: string;
  dnis?: string;
  direction?: 'inbound' | 'outbound';
  skill?: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  errorMsg?: string;
  // Grupo A — Identificação
  customerNumber?: string;
  organization?: string;
  // Grupo B — Qualidade
  disconnectedBy?: 'atendente' | 'cliente';
  numberOfHolds?: number;
  totalHoldTime?: number;
  numberOfTransfers?: number;
  numberOfConferences?: number;
  wrapupTime?: number;
  // Grupo C — Técnico/Auditoria — ausente para não-ADMIN (nunca chega no payload)
  codec?: string;
  missedRtpPackets?: number;
  decodingErrors?: number;
  switchCallId?: string;
  trunk?: string;
  captureType?: string;
  datasourceName?: string;
}

/** Grupo D — histórico de transferências (0..N por chamada). targetSwitchCallId
 * só vem preenchido para ADMIN. */
export interface CallTransferEvent {
  order: number;
  transferredAt?: string;
  disconnectedBy?: 'atendente' | 'cliente';
  targetExtension?: string;
  targetAgentName?: string;
  resolved: boolean;
  targetSwitchCallId?: string;
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

export interface CallEvaluationItem {
  id: number;
  itemId: number;
  nota: number;
  justificativa?: string;
  trechoReferencia?: string;
}

export interface CallEvaluation {
  id: number;
  audioFileId: number;
  scorecardId: number;
  notaTotal: number;
  isFailed: boolean;
  failReason?: string;
}

export interface InsightsDetailResponse {
  audioFile: CallAudioFile;
  segments: CallTranscriptSegment[];
  insights: CallInsight | null;
  findings: CallInsightFinding[];
  evaluation: CallEvaluation | null;
  evaluationItems: CallEvaluationItem[];
  transferEvents: CallTransferEvent[];
}

export interface InsightsDashboardSummary {
  totalChamadas: number;
  porCriticidade: Record<string, number>;
  porCategoria: Record<string, number>;
  achadosPorTipo: Record<string, number>;
  mediaNotaGeral: number;
  agentesAbaixoMedia: number;
  autoFailsNoPeriodo: number;
}

// ---- Insights — aba "Fichas" (scorecards, Fase 1 Quality Management, V38) ----
export interface ScorecardItemDto {
  id?: number;
  ordem: number;
  pergunta: string;
  peso: number;
  notaMaxima: number;
  isCritical: boolean;
}

export interface ScorecardDto {
  id: number;
  name: string;
  description?: string;
  isActive: boolean;
  version: number;
  items: ScorecardItemDto[];
  createdAt?: string;
  updatedAt?: string;
}

// ---- Insights — aba "Relatórios" (relatórios de performance por atendente, Fase 2 QM, V39) ----
export interface AgentReportItemAverage {
  itemId: number;
  pergunta: string;
  media: number;
}

export interface AgentReportFinding {
  tipo: string;
  descricao: string;
  trechoReferencia?: string;
  prioridade: string;
}

export interface AgentReportNarrative {
  pontosFortes: string[];
  pontosMelhoria: string[];
  recomendacoes: string[];
  comparacaoTextual?: string;
}

export interface AgentReportAggregate {
  totalChamadas: number;
  notaMedia?: number;
  autoFails: number;
  notaPorItem: AgentReportItemAverage[];
  achadosPorTipo: Record<string, number>;
}

export interface AgentReportContent {
  aggregate: AgentReportAggregate;
  achadosGraves: AgentReportFinding[];
  narrative: AgentReportNarrative | null;
}

export interface AgentReportItemDelta {
  itemId: number;
  pergunta: string;
  anterior?: number;
  atual?: number;
  delta?: number;
}

export interface AgentReportEvolution {
  previousReportId: number;
  partial: boolean;
  deltaNotaMedia?: number;
  deltaPorItem: AgentReportItemDelta[];
}

export interface AgentReportDto {
  id: number;
  agentName: string;
  dateFrom: string;
  dateTo: string;
  requestedBy: string;
  requestedAt: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  errorMsg?: string;
  content: AgentReportContent | null;
  previousReportId?: number;
  evolution: AgentReportEvolution | null;
  completedAt?: string;
}

export interface AgentEvolutionSnapshot {
  id: number;
  agentName: string;
  reportId: number;
  itemId?: number;
  metricKey: string;
  valor: number;
  createdAt: string;
}

// ---- Insights — aba "Meus Envios" (portal do supervisor, Fase 3 QM, V40) ----
export interface UploadFileSummary {
  id: number;
  callRef: string;
  agentName?: string;
  direction?: string;
  status: 'pending' | 'processing' | 'done' | 'error';
  errorMsg?: string;
  durationSeconds?: number;
}

export interface UploadBatchDto {
  id: string;
  uploadedBy: string;
  createdAt: string;
  fileCount: number;
  notes?: string;
  files: UploadFileSummary[] | null;
}

// Filtros de drill-down entre abas (Dashboard de Tendências, Custos IA e
// Processamento → Chamadas) — `id` filtra uma chamada exata (mesma PK
// CallAudioFile.id em todas as abas); os demais campos filtram por agregado.
export interface InsightsDrillDownFilters {
  id?: number;
  categoria?: string;
  criticidade?: string;
  findingType?: string;
  dateFrom?: string;
  dateTo?: string;
  isFailed?: boolean;
}

// ---- Insights — aba "Processamento" ----
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
