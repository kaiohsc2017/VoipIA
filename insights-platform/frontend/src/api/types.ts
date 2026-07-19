/**
 * types.ts — subset de tipos TypeScript da API AsteriskIA usado pela SPA de Insights.
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

// Filtros de drill-down entre abas (Dashboard de Tendências, Custos IA e
// Processamento → Chamadas) — `id` filtra uma chamada exata (mesma PK
// CallAudioFile.id em todas as abas); os demais campos filtram por agregado.
export interface InsightsDrillDownFilters {
  id?: number;
  categoria?: string;
  criticidade?: string;
  findingType?: string;
}

// ---- Insights — aba "Custos IA" / "Dashboard de Custos" ----
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
