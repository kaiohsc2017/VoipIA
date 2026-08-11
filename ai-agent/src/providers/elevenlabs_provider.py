"""
providers/elevenlabs_provider.py — ElevenLabs TTS com streaming.

Envia áudio em chunks MP3/PCM conforme chegam da API ElevenLabs.
Usa a biblioteca elevenlabs oficial.
"""
from __future__ import annotations

import asyncio
import logging

from src.providers.base import BaseAIProvider, ProviderError
from src.protocol import write_audio

logger = logging.getLogger("asteriskia.provider.elevenlabs")

SAMPLE_RATE = 8000

_el_client = None
_el_key    = ""


def _get_el(api_key: str):
    global _el_client, _el_key
    try:
        from elevenlabs.client import ElevenLabs as _EL
    except ImportError:
        raise ImportError("Instale: pip install elevenlabs")
    if _el_client is None or _el_key != api_key:
        _el_client = _EL(api_key=api_key)
        _el_key    = api_key
    return _el_client


def _resample(pcm: bytes, from_hz: int) -> bytes:
    import numpy as np
    samples   = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    n_new     = int(len(samples) * SAMPLE_RATE / from_hz)
    resampled = np.interp(
        np.linspace(0, len(samples) - 1, n_new),
        np.arange(len(samples)),
        samples,
    )
    return resampled.astype(np.int16).tobytes()


class ElevenLabsProvider(BaseAIProvider):

    def __init__(self, model_id: str, api_key: str):
        self._model_id = model_id
        self._api_key  = api_key

    @property
    def provider_id(self) -> str:
        return "elevenlabs"

    @property
    def model_id(self) -> str:
        return self._model_id

    async def transcribe(self, pcm_data: bytes) -> str:
        raise ProviderError("elevenlabs", self._model_id,
            NotImplementedError("ElevenLabs não suporta STT"))

    async def generate_response_with_tools(self, system_instruction: str, history: list[dict]) -> str:
        raise ProviderError("elevenlabs", self._model_id,
            NotImplementedError("ElevenLabs não suporta LLM"))

    async def synthesize_speech_streaming(
        self, text: str, writer, record: list[bytes] | None = None
    ) -> tuple[bool, float]:
        try:
            client = _get_el(self._api_key)

            def _iter_chunks():
                # ElevenLabs retorna PCM 22050Hz ou 44100Hz dependendo do modelo
                audio_stream = client.text_to_speech.convert_as_stream(
                    voice_id="Rachel",            # voz padrão PT-BR
                    text=text,
                    model_id=self._model_id,
                    output_format="pcm_22050",    # PCM 22kHz, 16bit
                )
                for chunk in audio_stream:
                    if isinstance(chunk, bytes) and chunk:
                        yield _resample(chunk, 22050)

            chunks = await asyncio.to_thread(lambda: list(_iter_chunks()))
            total_bytes = 0
            for chunk in chunks:
                sent = await write_audio(writer, chunk, record=record)
                if not sent:
                    return False, total_bytes / (SAMPLE_RATE * 2)
                total_bytes += len(chunk)
            return True, total_bytes / (SAMPLE_RATE * 2)
        except Exception as e:
            raise ProviderError("elevenlabs", self._model_id, e) from e
