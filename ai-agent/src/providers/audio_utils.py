"""
providers/audio_utils.py — Utilitários de conversão de áudio PCM compartilhados
entre providers (Gemini, OpenAI, local/Whisper).

Extraído de 3 implementações quase idênticas (gemini_service.py, local_provider.py,
openai_provider.py) — mesma lógica de empacotamento WAV e resample linear.
"""
import io
import wave

import numpy as np


def pcm_to_wav(pcm: bytes, rate: int = 8000) -> bytes:
    """Converte PCM 16bit little-endian mono para WAV em memória (stdlib wave)."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)
    return buf.getvalue()


def resample_pcm(pcm: bytes, from_hz: int, to_hz: int) -> bytes:
    """Resample PCM 16bit little-endian de from_hz para to_hz via interpolação linear (numpy)."""
    if from_hz == to_hz:
        return pcm
    samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    num_out = int(len(samples) * to_hz / from_hz)
    x_old = np.linspace(0, 1, len(samples))
    x_new = np.linspace(0, 1, num_out)
    resampled = np.interp(x_new, x_old, samples)
    return resampled.astype(np.int16).tobytes()
