"""models.py — Pydantic schemas"""
from pydantic import BaseModel, Field
from typing import Optional, List, Any
from uuid import UUID
from datetime import datetime

class ServerCreate(BaseModel):
    name: str
    host: str
    port: int = 22
    username: str
    auth_type: str = "password"   # 'password' | 'key'
    password: Optional[str] = None
    ssh_key: Optional[str] = None
    tags: List[str] = Field(default_factory=list)

class ServerOut(ServerCreate):
    id: UUID
    active: bool
    created_at: datetime
    class Config: from_attributes = True

class AgentSchedule(BaseModel):
    type: str = "interval"   # 'interval' | 'cron' | 'always' | 'once'
    value: Optional[str] = "5m"   # ex: '5m', '1h', '0 * * * *'
    active: bool = True

class AgentCreate(BaseModel):
    name: str
    description: Optional[str] = None
    type: str   # 'ssh_test' | 'web_monitor' | 'log_monitor' | 'database'
    skill: str  # contexto/prompt do especialista
    server_ids: List[str] = Field(default_factory=list)
    target_urls: List[str] = Field(default_factory=list)
    rules: dict = Field(default_factory=dict)
    schedule: AgentSchedule = Field(default_factory=AgentSchedule)
    notify_telegram: bool = False
    telegram_chat: Optional[str] = None
    notify_email: bool = False
    notify_email_to: Optional[str] = None
    notify_webhook: bool = False
    notify_webhook_url: Optional[str] = None
    on_failure_trigger_agent_id: Optional[str] = None

class AgentOut(AgentCreate):
    id: UUID
    status: str
    last_run: Optional[datetime]
    next_run: Optional[datetime]
    created_at: datetime
    class Config: from_attributes = True

class ExecutionOut(BaseModel):
    id: UUID
    agent_id: UUID
    session_id: str
    status: str
    started_at: datetime
    finished_at: Optional[datetime]
    duration_s: Optional[float]
    total_checks: int
    passed_checks: int
    failed_checks: int
    summary: Optional[str]
    report_json: dict
    class Config: from_attributes = True

class LogLine(BaseModel):
    id: int
    execution_id: UUID
    agent_id: UUID
    ts: datetime
    level: str
    server: Optional[str]
    message: str
    raw_output: Optional[str]

class MemoryEntry(BaseModel):
    agent_id: UUID
    mtype: str
    title: str
    content: str
    metadata: dict = Field(default_factory=dict)
    tags: List[str] = Field(default_factory=list)

class AlertOut(BaseModel):
    id: int
    agent_id: UUID
    level: str
    message: str
    channel: str
    sent_at: datetime
    delivered: bool
