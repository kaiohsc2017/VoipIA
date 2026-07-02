"""
providers/openai_provider.py — Provedor OpenAI (Whisper + GPT + TTS).

STT: Whisper API — recebe PCM, converte para WAV e envia.
LLM: Chat Completions API.
TTS: Speech API com streaming de chunks de áudio.
"""
from __future__ import annotations

import asyncio
import io
import logging
import struct
import wave

from src.providers.base import BaseAIProvider, ProviderError
from src.protocol import write_audio

logger = logging.getLogger("asteriskia.provider.openai")

_openai_client = None
_openai_key    = ""

SAMPLE_RATE   = 8000
FRAME_BYTES   = 640  # 320 samples × 2 bytes
OPENAI_RATE   = 24000  # TTS retorna 24kHz PCM16


def _get_openai(api_key: str):
    global _openai_client, _openai_key
    try:
        import openai as _sdk
    except ImportError:
        raise ImportError("Instale: pip install openai")
    if _openai_client is None or _openai_key != api_key:
        _openai_client = _sdk.OpenAI(api_key=api_key)
        _openai_key    = api_key
    return _openai_client


def _pcm_to_wav(pcm: bytes, rate: int = 8000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)
    return buf.getvalue()


def _resample(pcm: bytes, from_hz: int, to_hz: int) -> bytes:
    import numpy as np
    samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    n_new   = int(len(samples) * to_hz / from_hz)
    resampled = np.interp(
        np.linspace(0, len(samples) - 1, n_new),
        np.arange(len(samples)),
        samples,
    )
    return resampled.astype(np.int16).tobytes()


class OpenAIProvider(BaseAIProvider):

    def __init__(self, model_id: str, api_key: str, capability: str):
        self._model_id   = model_id
        self._api_key    = api_key
        self._capability = capability  # STT | LLM | TTS

    @property
    def provider_id(self) -> str:
        return "openai"

    @property
    def model_id(self) -> str:
        return self._model_id

    # ── STT ──────────────────────────────────────────────────────────────────

    async def transcribe(self, pcm_data: bytes) -> str:
        if self._capability not in ("STT",):
            raise ProviderError("openai", self._model_id,
                ValueError(f"Modelo {self._model_id} não é STT"))
        try:
            client  = _get_openai(self._api_key)
            wav     = _pcm_to_wav(pcm_data, SAMPLE_RATE)
            wav_buf = io.BytesIO(wav)
            wav_buf.name = "audio.wav"

            def _call():
                resp = client.audio.transcriptions.create(
                    model=self._model_id,
                    file=wav_buf,
                    language="pt",
                )
                return resp.text.strip()

            return await asyncio.to_thread(_call)
        except Exception as e:
            raise ProviderError("openai", self._model_id, e) from e

    # ── LLM ──────────────────────────────────────────────────────────────────

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        try:
            client   = _get_openai(self._api_key)
            messages = [{"role": "system", "content": system_instruction}]
            for turn in history:
                role    = "assistant" if turn.get("role") == "model" else "user"
                content = turn.get("text","") or turn.get("content","")
                messages.append({"role": role, "content": content})

            def _call():
                resp = client.chat.completions.create(
                    model=self._model_id,
                    messages=messages,
                    max_tokens=512,
                )
                return resp.choices[0].message.content or ""

            return await asyncio.to_thread(_call)
        except Exception as e:
            raise ProviderError("openai", self._model_id, e) from e

    # ── TTS ──────────────────────────────────────────────────────────────────

    async def synthesize_speech_streaming(
        self, text: str, writer, record: list[bytes] | None = None
    ) -> tuple[bool, float]:
        try:
            client = _get_openai(self._api_key)

            def _iter_chunks():
                with client.audio.speech.with_streaming_response.create(
                    model=self._model_id,
                    voice="nova",
                    input=text,
                    response_format="pcm",  # 24kHz PCM16
                ) as resp:
                    for chunk in resp.iter_bytes(chunk_size=FRAME_BYTES * 4):
                        if chunk:
                            yield _resample(chunk, OPENAI_RATE, SAMPLE_RATE)

            chunks = await asyncio.to_thread(lambda: list(_iter_chunks()))
            total_bytes = 0
            for chunk in chunks:
                sent = await write_audio(writer, chunk, record=record)
                if not sent:
                    return False, total_bytes / (SAMPLE_RATE * 2)
                total_bytes += len(chunk)
            return True, total_bytes / (SAMPLE_RATE * 2)
        except Exception as e:
            raise ProviderError("openai", self._model_id, e) from e

    async def synthesize_speech(self, text: str) -> bytes:
        try:
            client = _get_openai(self._api_key)

            def _call():
                resp = client.audio.speech.create(
                    model=self._model_id,
                    voice="nova",
                    input=text,
                    response_format="pcm",
                )
                return _resample(resp.content, OPENAI_RATE, SAMPLE_RATE)

            return await asyncio.to_thread(_call)
        except Exception as e:
            raise ProviderError("openai", self._model_id, e) from e
