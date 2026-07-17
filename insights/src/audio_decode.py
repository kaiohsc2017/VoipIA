"""
audio_decode.py — Decodifica o WAV proprietário do Verint para PCM.

Achado da Fase 0: o .wav gerado pelo Verint usa codec G.729A com um chunk
extra ("WAVESIGN", provável assinatura digital de tamper-evidence) antes do
"fmt " — não decodifica com a stdlib `wave` do Python (format tag 0x83 não é
reconhecido), mas o `ffmpeg` (já usado no ai-agent/Dockerfile) ignora o chunk
desconhecido e decodifica nativamente, testado e confirmado funcionando.
"""

from __future__ import annotations

import asyncio
import io
import logging
import subprocess
import wave

logger = logging.getLogger("asteriskia.insights.audio_decode")

PCM_SAMPLE_RATE = 8000


class AudioDecodeError(Exception):
    """Falha ao decodificar o WAV de origem (arquivo corrompido, ffmpeg ausente, etc.)."""


def _decode_sync(wav_path: str) -> bytes:
    """Roda ffmpeg como subprocesso, capturando PCM16LE 8kHz mono na stdout."""
    try:
        result = subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", wav_path,
                "-f", "s16le", "-ar", str(PCM_SAMPLE_RATE), "-ac", "1",
                "-",
            ],
            capture_output=True,
            check=True,
            timeout=120,
        )
    except subprocess.CalledProcessError as e:
        raise AudioDecodeError(
            f"ffmpeg falhou ao decodificar {wav_path}: {e.stderr.decode(errors='replace')}"
        ) from e
    except subprocess.TimeoutExpired as e:
        raise AudioDecodeError(f"ffmpeg excedeu o tempo limite decodificando {wav_path}") from e

    return result.stdout


async def decode_to_pcm(wav_path: str) -> bytes:
    """Decodifica o .wav Verint para PCM16LE mono 8kHz, em thread separada."""
    return await asyncio.to_thread(_decode_sync, wav_path)


def pcm_to_wav(pcm: bytes, rate: int = PCM_SAMPLE_RATE) -> bytes:
    """Empacota PCM16LE mono em um WAV padrão em memória — formato aceito
    pela API do Gemini para envio inline."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)
    return buf.getvalue()
