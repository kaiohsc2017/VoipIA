/**
 * types.ts — TypeScript types para a API AsteriskIA
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

// ---- Dashboard KPIs ----
export interface DashboardStats {
  callsToday: number;
  testsToday: number;
  testSuccessRate: number;
  activeAlerts: number;
  recentResults: TestResult[];
}
