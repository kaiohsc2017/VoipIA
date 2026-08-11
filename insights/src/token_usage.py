"""
token_usage.py — Extração de uso de tokens das respostas do Gemini.

Mesmo formato de ai-agent/src/services/token_usage.py (TokenUsage), para
manter consistência com o dashboard de Custos de IA já existente — este
serviço popula stt_tokens_*/llm_tokens_* de call_audio_files com a mesma
nomenclatura usada em call_records.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class TokenUsage:
    input_tokens: int = 0
    output_tokens: int = 0


def extract_usage(resp) -> TokenUsage | None:
    """Lê usage_metadata da resposta do SDK google-genai. Retorna None se ausente."""
    meta = getattr(resp, "usage_metadata", None)
    if meta is None:
        return None
    return TokenUsage(
        input_tokens=getattr(meta, "prompt_token_count", 0) or 0,
        output_tokens=getattr(meta, "candidates_token_count", 0) or 0,
    )
