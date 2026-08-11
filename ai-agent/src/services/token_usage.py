"""
services/token_usage.py — Acumulador de uso de tokens de IA por chamada.

Cada instância de AIService (uma por chamada — ver JiraCallFlow.__init__) mantém
um CallUsageAccumulator próprio, populado a partir do `last_usage` que os
provedores expõem após cada chamada real ao modelo (hoje só GeminiProvider
implementa — os demais provedores simplesmente não populam `last_usage`, e o
uso daquela capability fica zerado). Usado para montar o payload de custo
enviado a /api/v1/calls/register (persistência e precificação ficam na Fase 2,
no backend).
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class TokenUsage:
    """Tokens consumidos em uma única chamada ao modelo."""
    input_tokens: int = 0
    output_tokens: int = 0


@dataclass
class _CapabilityUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    model_id: str | None = None


@dataclass
class CallUsageAccumulator:
    """Acumula o uso de tokens de STT/LLM/TTS ao longo de uma chamada inteira."""
    stt: _CapabilityUsage = field(default_factory=_CapabilityUsage)
    llm: _CapabilityUsage = field(default_factory=_CapabilityUsage)
    tts: _CapabilityUsage = field(default_factory=_CapabilityUsage)

    def add(self, capability: str, model_id: str, usage: TokenUsage | None) -> None:
        """Soma o uso de uma chamada ao modelo na capability correspondente.
        Chamado após cada tentativa bem-sucedida no FallbackRouter (AIService._run) —
        `usage` vem None para provedores que ainda não reportam tokens."""
        if usage is None:
            return
        bucket: _CapabilityUsage | None = getattr(self, capability.lower(), None)
        if bucket is None:
            return
        bucket.input_tokens += usage.input_tokens
        bucket.output_tokens += usage.output_tokens
        bucket.model_id = model_id

    def to_payload(self) -> dict:
        """Formato consumido por CallRecorder.create_jira_issue — chaves alinhadas
        ao futuro DTO do backend (RegisterCallRequest, Fase 2). O backend hoje
        ignora campos desconhecidos no JSON (fail-on-unknown-properties=false),
        então enviar isso já é seguro antes da Fase 2 existir."""
        return {
            "sttTokensIn": self.stt.input_tokens,
            "sttTokensOut": self.stt.output_tokens,
            "sttModel": self.stt.model_id,
            "llmTokensIn": self.llm.input_tokens,
            "llmTokensOut": self.llm.output_tokens,
            "llmModel": self.llm.model_id,
            "ttsTokensIn": self.tts.input_tokens,
            "ttsTokensOut": self.tts.output_tokens,
            "ttsModel": self.tts.model_id,
        }
