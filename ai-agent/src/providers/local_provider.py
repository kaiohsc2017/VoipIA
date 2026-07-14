"""
providers/local_provider.py — Provedor local (Ollama + Whisper).

STT: OpenAI Whisper rodando localmente via faster-whisper ou whisper.cpp.
     Fallback: usa a API REST do Ollama se disponível.
LLM: Ollama — qualquer modelo disponível em http://localhost:11434.

Vantagens: sem custo de API, dados não saem do servidor, funciona offline.
Requisito: Ollama instalado e rodando na mesma rede Docker do ai-agent.
"""
from __future__ import annotations

import asyncio
import io
import logging

import httpx

from src.providers.audio_utils import pcm_to_wav
from src.providers.base import BaseAIProvider, ProviderError

logger = logging.getLogger("asteriskia.provider.local")

OLLAMA_BASE_URL = "http://host.docker.internal:11434"  # acessa o host a partir do container
SAMPLE_RATE     = 8000


class LocalProvider(BaseAIProvider):
    """
    Provedor local usando Ollama (LLM) e Whisper (STT).
    TTS não suportado localmente — use Gemini ou ElevenLabs para TTS.
    """

    def __init__(self, model_id: str, capability: str):
        self._model_id   = model_id
        self._capability = capability

    @property
    def provider_id(self) -> str:
        return "local"

    @property
    def model_id(self) -> str:
        return self._model_id

    # ── STT via Whisper local ─────────────────────────────────────────────────

    async def transcribe(self, pcm_data: bytes) -> str:
        """
        Transcreve áudio usando Whisper local.
        Tenta faster-whisper primeiro (mais rápido), fallback para whisper-python.
        """
        try:
            return await asyncio.to_thread(self._transcribe_sync, pcm_data)
        except Exception as e:
            raise ProviderError("local", self._model_id, e) from e

    def _transcribe_sync(self, pcm_data: bytes) -> str:
        wav_bytes = pcm_to_wav(pcm_data)

        # Tenta faster-whisper (preferido — menor latência)
        try:
            from faster_whisper import WhisperModel  # type: ignore
            model_size = self._model_id.replace("whisper-", "").replace("whisper_", "") or "medium"
            model = WhisperModel(model_size, device="cpu", compute_type="int8")
            audio_buf = io.BytesIO(wav_bytes)
            segments, _ = model.transcribe(audio_buf, language="pt")
            return " ".join(s.text.strip() for s in segments).strip()
        except ImportError:
            pass

        # Fallback: whisper Python oficial
        try:
            import whisper  # type: ignore
            import tempfile, os
            model_name = self._model_id.replace("whisper-", "") or "medium"
            model = whisper.load_model(model_name)
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
                f.write(wav_bytes)
                tmp_path = f.name
            try:
                result = model.transcribe(tmp_path, language="pt")
                return result.get("text", "").strip()
            finally:
                os.unlink(tmp_path)
        except ImportError:
            raise RuntimeError(
                "Nenhum backend Whisper disponível. "
                "Instale: pip install faster-whisper   (ou)   pip install openai-whisper"
            )

    # ── LLM via Ollama ────────────────────────────────────────────────────────

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        try:
            messages = [{"role": "system", "content": system_instruction}]
            for turn in history:
                role    = "assistant" if turn.get("role") == "model" else "user"
                content = turn.get("text", "") or turn.get("content", "")
                messages.append({"role": role, "content": content})

            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.post(
                    f"{OLLAMA_BASE_URL}/api/chat",
                    json={
                        "model":    self._model_id,
                        "messages": messages,
                        "stream":   False,
                        "options":  {"num_predict": 512},
                    },
                )
                resp.raise_for_status()
                data = resp.json()
                return data.get("message", {}).get("content", "").strip()

        except httpx.ConnectError:
            raise ProviderError("local", self._model_id,
                ConnectionError(
                    f"Ollama não acessível em {OLLAMA_BASE_URL}. "
                    "Verifique se está instalado e rodando no host."
                ))
        except Exception as e:
            raise ProviderError("local", self._model_id, e) from e

    # ── TTS — não suportado ───────────────────────────────────────────────────

    async def synthesize_speech_streaming(
        self, text: str, writer, record: list[bytes] | None = None
    ) -> tuple[bool, float]:
        raise ProviderError("local", self._model_id,
            NotImplementedError(
                "TTS local não disponível — configure ElevenLabs ou Gemini como fallback TTS"
            ))
