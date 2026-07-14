"""Testes de CallUsageAccumulator — lógica pura, sem dependência do SDK Gemini."""
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.services.token_usage import CallUsageAccumulator, TokenUsage  # noqa: E402


def test_add_acumula_tokens_na_capability_correta():
    acc = CallUsageAccumulator()

    acc.add("STT", "gemini-2.5-flash", TokenUsage(input_tokens=100, output_tokens=10))
    acc.add("LLM", "gemini-2.5-flash", TokenUsage(input_tokens=200, output_tokens=50))

    assert acc.stt.input_tokens == 100
    assert acc.stt.output_tokens == 10
    assert acc.stt.model_id == "gemini-2.5-flash"
    assert acc.llm.input_tokens == 200
    assert acc.tts.input_tokens == 0


def test_add_soma_multiplas_chamadas_da_mesma_capability():
    acc = CallUsageAccumulator()

    acc.add("LLM", "gemini-2.5-flash", TokenUsage(input_tokens=100, output_tokens=20))
    acc.add("LLM", "gemini-2.5-flash", TokenUsage(input_tokens=50, output_tokens=15))

    assert acc.llm.input_tokens == 150
    assert acc.llm.output_tokens == 35


def test_add_ignora_usage_none_sem_quebrar():
    acc = CallUsageAccumulator()

    acc.add("TTS", "gemini-2.5-flash-preview-tts", None)

    assert acc.tts.input_tokens == 0
    assert acc.tts.model_id is None


def test_add_ignora_capability_desconhecida():
    acc = CallUsageAccumulator()

    acc.add("EMBEDDING", "algum-modelo", TokenUsage(input_tokens=5, output_tokens=1))

    assert acc.to_payload()["sttTokensIn"] == 0
    assert acc.to_payload()["llmTokensIn"] == 0
    assert acc.to_payload()["ttsTokensIn"] == 0


def test_to_payload_formata_chaves_esperadas_pelo_backend():
    acc = CallUsageAccumulator()
    acc.add("STT", "gemini-2.5-flash", TokenUsage(input_tokens=10, output_tokens=1))
    acc.add("LLM", "gemini-2.5-flash", TokenUsage(input_tokens=20, output_tokens=8))
    acc.add("TTS", "gemini-2.5-flash-preview-tts", TokenUsage(input_tokens=3, output_tokens=0))

    payload = acc.to_payload()

    assert payload == {
        "sttTokensIn": 10, "sttTokensOut": 1, "sttModel": "gemini-2.5-flash",
        "llmTokensIn": 20, "llmTokensOut": 8, "llmModel": "gemini-2.5-flash",
        "ttsTokensIn": 3, "ttsTokensOut": 0, "ttsModel": "gemini-2.5-flash-preview-tts",
    }
