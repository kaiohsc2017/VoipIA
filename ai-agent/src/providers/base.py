"""
providers/base.py — Interface base para todos os provedores de IA.

Qualquer provedor novo implementa esta ABC e é automaticamente suportado
pelo FallbackRouter sem alteração nos flows (JiraCallFlow, ZabbixAlertFlow).
"""
from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod

from src.services.token_usage import TokenUsage


class BaseAIProvider(ABC):
    """
    Contrato que todo provedor de IA deve implementar.

    Cada método deve levantar ProviderError em caso de falha recuperável
    (quota, timeout, erro 5xx) para que o FallbackRouter tente o próximo.
    """

    # Tokens da última chamada real ao modelo — provedores que reportam consumo
    # (hoje só GeminiProvider) sobrescrevem este atributo de instância após cada
    # chamada; AIService._run lê isso logo após invocar o método. Fica None para
    # provedores que ainda não implementam a captura.
    last_usage: TokenUsage | None = None

    @property
    @abstractmethod
    def provider_id(self) -> str:
        """Identificador único: gemini | anthropic | openai | …"""

    @property
    @abstractmethod
    def model_id(self) -> str:
        """ID do modelo configurado nesta instância."""

    @abstractmethod
    async def transcribe(self, pcm_data: bytes) -> str:
        """
        STT — converte áudio PCM 8kHz/16bit/mono em texto.
        Retorna string vazia se não conseguiu transcrever.
        """

    async def transcribe_with_hint(self, pcm_data: bytes, hint: str) -> str:
        """
        STT com contexto semântico — melhora a acurácia para dados estruturados.

        O hint descreve o tipo de dado esperado (ramal, login, prioridade…),
        permitindo que o modelo STT use o contexto para acertos mais precisos.

        Implementação padrão ignora o hint e chama transcribe() normalmente.
        Provedores que suportam contexto devem sobrescrever este método.
        """
        return await self.transcribe(pcm_data)

    @abstractmethod
    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        """
        LLM — gera resposta a partir de histórico de conversa.
        Pode executar function calling internamente.
        """

    @abstractmethod
    async def synthesize_speech_streaming(
        self, text: str, writer, record: list[bytes] | None = None
    ) -> tuple[bool, float]:
        """
        TTS streaming — envia chunks de áudio ao AudioSocket conforme chegam.
        Retorna (sucesso, duração_em_segundos). `record` acumula o PCM enviado, se informado.
        """

    async def synthesize_speech(self, text: str) -> bytes:
        """
        TTS bloqueante — retorna bytes PCM completo.
        Implementação padrão coleta o streaming (pode ser sobrescrita).
        """
        raise NotImplementedError(f"{self.provider_id} não implementa synthesize_speech bloqueante")


class ProviderError(Exception):
    """
    Erro recuperável de um provedor — FallbackRouter tenta o próximo.
    Exemplos: quota excedida, timeout, erro 5xx, key inválida.
    """
    def __init__(self, provider: str, model: str, cause: Exception):
        self.provider = provider
        self.model    = model
        self.cause    = cause
        super().__init__(f"[{provider}/{model}] {type(cause).__name__}: {cause}")


class ProviderUnavailableError(Exception):
    """Todos os provedores da chain falharam."""
    def __init__(self, capability: str, errors: list[ProviderError]):
        self.errors = errors
        msgs = "; ".join(str(e) for e in errors)
        super().__init__(f"Nenhum provedor disponível para {capability}: {msgs}")
