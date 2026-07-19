// Tipos do backend FastAPI da Plataforma de Agentes (agents-platform/backend/).
// Diferente do Telecom/Insights: sem envelope de resposta, paginação
// offset/limit (não page/size), erros em `detail` (string ou array Pydantic).

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
}

// ---- Auth (backend Telecom — login e streaming-token) ----
export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token?: string;
  accessToken?: string;
  access_token?: string;
  username?: string;
  requiresTotp?: boolean;
  tempToken?: string;
  displayName?: string;
  firstLoginCompleted?: boolean;
}

// ---- Agents ----
export type AgentType = 'ssh_test' | 'web_monitor' | 'log_monitor' | 'database';
export type AgentStatus = 'idle' | 'running' | 'success' | 'error' | 'partial' | 'paused';
export type ScheduleType = 'interval' | 'cron' | 'always' | 'once';

export interface AgentCheck {
  name?: string;
  cmd: string;
  expect?: string;
  fix_hint?: string;
}

export interface AgentRules {
  checks?: AgentCheck[];
  use_ai_on_failure?: boolean;
}

export interface AgentSchedule {
  type: ScheduleType;
  value?: string;
  active?: boolean;
}

export interface Agent {
  id: string;
  name: string;
  description?: string;
  type: AgentType;
  skill?: string;
  server_ids?: string[];
  target_urls?: string[];
  rules: AgentRules;
  schedule: AgentSchedule;
  notify_telegram?: boolean;
  telegram_chat?: string;
  notify_email?: boolean;
  notify_email_to?: string;
  notify_webhook?: boolean;
  notify_webhook_url?: string;
  status: AgentStatus;
  last_run?: string;
  next_run?: string;
}

export type AgentFormData = Omit<Agent, 'id' | 'status' | 'last_run' | 'next_run'>;

// ---- Servers ----
export type ServerAuthType = 'password' | 'key';

export interface ServerEntry {
  id: string;
  name: string;
  host: string;
  port: number;
  username: string;
  auth_type: ServerAuthType;
  password?: string;
  ssh_key?: string;
  tags: string[];
}

export interface ServerTestResult {
  ok: boolean;
  output?: string;
  error?: string;
}

// ---- Executions / Logs ----
export interface Execution {
  id: string;
  agent_id: string;
  agent_name: string;
  status: AgentStatus;
  passed_checks?: number;
  total_checks?: number;
  failed_checks?: number;
  duration_s?: number;
  started_at: string;
}

export interface LogEntry {
  ts: string;
  server?: string;
  level: 'info' | 'success' | 'warning' | 'error';
  message: string;
}

// ---- Dashboard ----
export interface DashboardSummary {
  active_agents: number;
  executions_24h: { ok: number; errors: number };
  alerts_24h: number;
  recent_executions: Execution[];
}

export interface PeriodRow {
  agent_name: string;
  total: number;
  ok: number;
  errors: number;
  avg_duration?: number;
  failures?: number;
}

// ---- Alertas ----
export interface AlertEntry {
  id: string;
  agent_name: string;
  level: 'info' | 'warning' | 'error' | 'critical';
  channel: string;
  message: string;
  sent_at: string;
}

// ---- Base de Conhecimento ----
export interface KnowledgeDoc {
  id: string;
  title?: string;
  filename: string;
  tags: string[];
  created_at: string;
}

// ---- Secrets ----
export interface AgentSecret {
  id?: string;
  key: string;
  created_at: string;
}

// ---- Config. IA ----
export interface LlmStatus {
  ready: boolean;
  reason?: string;
  provider?: string;
  model?: string;
  enabled?: boolean;
  env_file?: string;
  file_exists?: boolean;
}

export interface LlmProvider {
  id: string;
  label?: string;
  models: string[];
}

export type LlmConfigForm = Record<string, string>;

export interface LlmTestResult {
  ok: boolean;
  provider?: string;
  model?: string;
  response?: string;
  error?: string;
}
