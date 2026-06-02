"""
gemini_service.py — Serviço de IA via Google Gemini (SDK google-genai)

Encapsula STT (Speech-to-Text), LLM e TTS (Text-to-Speech).

Formatos de áudio:
  - Entrada (STT): PCM 8kHz 16bit signed little-endian mono (formato Asterisk)
  - Saída (TTS):   PCM 8kHz 16bit signed little-endian mono (formato Asterisk)

Nota: usa o novo SDK `google-genai` (pip install google-genai),
não o legado `google-generativeai`.
"""

import io
import logging
import asyncio
import struct
import numpy as np
import google.genai as genai
import google.genai.types as genai_types
from src.config import GEMINI_API_KEY, GEMINI_MODEL_STT, GEMINI_MODEL_LLM, GEMINI_MODEL_TTS

logger = logging.getLogger("asteriskia.gemini")


class GeminiService:
    """
    Serviço centralizado de IA usando Google Gemini.

    Métodos públicos:
      - transcribe(pcm_data: bytes) -> str
      - synthesize_speech(text: str) -> bytes
      - generate_response(prompt: str, context: str) -> str
    """

    def __init__(self):
        self._client = genai.Client(api_key=GEMINI_API_KEY)

    async def transcribe(self, pcm_data: bytes) -> str:
        """
        Converte áudio PCM (formato Asterisk) em texto via Gemini.

        Args:
            pcm_data: Áudio bruto PCM 8kHz/16bit/mono do Asterisk

        Returns:
            Texto transcrito ou string vazia em caso de erro
        """
        try:
            # Converte PCM bruto para WAV em memória
            wav_bytes = _pcm_to_wav(pcm_data, sample_rate=8000)

            # Executa STT em thread separada (API síncrona)
            result = await asyncio.to_thread(
                self._transcribe_sync, wav_bytes
            )
            return result.strip()
        except Exception as e:
            logger.error("Erro no STT: %s", e)
            return ""

    async def synthesize_speech(self, text: str) -> bytes:
        """
        Converte texto em áudio PCM (formato Asterisk) via Gemini TTS.

        Args:
            text: Texto a ser sintetizado em voz

        Returns:
            Bytes de áudio PCM 8kHz/16bit/mono compatível com Asterisk
        """
        try:
            audio_pcm = await asyncio.to_thread(
                self._tts_sync, text
            )
            return audio_pcm
        except Exception as e:
            logger.error("Erro no TTS: %s", e)
            # Retorna silêncio em caso de erro (320 bytes = 20ms @ 8kHz)
            return b'\x00' * 320

    async def generate_response(self, prompt: str, context: str = "") -> str:
        """
        Gera uma resposta de texto via Gemini LLM.
        Usado para interpretar respostas do usuário e mapear aos campos do Jira.

        Args:
            prompt: Instrução para o modelo
            context: Contexto adicional (histórico da conversa)

        Returns:
            Resposta gerada pelo LLM
        """
        try:
            full_prompt = f"{context}\n\n{prompt}" if context else prompt
            result = await asyncio.to_thread(
                self._llm_sync, full_prompt
            )
            return result.strip()
        except Exception as e:
            logger.error("Erro no LLM: %s", e)
            return ""

    # ------------------------------------------------------------------
    # Métodos privados síncronos (executados em thread pool)
    # ------------------------------------------------------------------

    def _transcribe_sync(self, wav_bytes: bytes) -> str:
        """STT via Gemini — envia WAV e retorna transcrição."""
        response = self._client.models.generate_content(
            model=GEMINI_MODEL_STT,
            contents=[
                genai_types.Content(parts=[
                    genai_types.Part(text=(
                        "Transcreva exatamente o que foi dito neste áudio em português do Brasil. "
                        "Retorne apenas o texto transcrito, sem pontuação adicional."
                    )),
                    genai_types.Part(inline_data=genai_types.Blob(
                        mime_type="audio/wav",
                        data=wav_bytes
                    )),
                ])
            ]
        )
        return response.text or ""

    def _tts_sync(self, text: str) -> bytes:
        """
        TTS via Gemini — retorna áudio PCM 8kHz/16bit/mono.

        O modelo TTS do Gemini retorna áudio em formato PCM L16 24kHz.
        Fazemos o resample para 8kHz (formato Asterisk).
        """
        response = self._client.models.generate_content(
            model=GEMINI_MODEL_TTS,
            contents=text,
            config=genai_types.GenerateContentConfig(
                response_modalities=["AUDIO"],
                speech_config=genai_types.SpeechConfig(
                    voice_config=genai_types.VoiceConfig(
                        prebuilt_voice_config=genai_types.PrebuiltVoiceConfig(
                            voice_name="Aoede"  # Voz feminina, boa para PT-BR
                        )
                    )
                )
            )
        )

        # Extrai dados de áudio bruto da resposta
        audio_data = response.candidates[0].content.parts[0].inline_data.data

        # O Gemini TTS retorna PCM L16 @ 24kHz — resample para 8kHz (Asterisk)
        return _resample_pcm(audio_data, from_hz=24000, to_hz=8000)

    def _llm_sync(self, prompt: str) -> str:
        """LLM via Gemini — gera resposta de texto."""
        response = self._client.models.generate_content(
            model=GEMINI_MODEL_LLM,
            contents=prompt,
        )
        return response.text or ""


# ------------------------------------------------------------------
# Funções utilitárias de conversão de áudio
# (sem dependência de soundfile — usa apenas numpy + stdlib)
# ------------------------------------------------------------------

def _pcm_to_wav(pcm_data: bytes, sample_rate: int = 8000) -> bytes:
    """
    Converte PCM 16bit little-endian para WAV em memória.
    Usa apenas stdlib struct (sem soundfile).
    """
    num_samples = len(pcm_data) // 2  # 2 bytes por sample (16bit)
    num_channels = 1
    bits_per_sample = 16
    byte_rate = sample_rate * num_channels * bits_per_sample // 8
    block_align = num_channels * bits_per_sample // 8
    data_size = len(pcm_data)
    header_size = 44

    buf = io.BytesIO()
    # RIFF header
    buf.write(b'RIFF')
    buf.write(struct.pack('<I', header_size - 8 + data_size))
    buf.write(b'WAVE')
    # fmt chunk
    buf.write(b'fmt ')
    buf.write(struct.pack('<I', 16))           # chunk size
    buf.write(struct.pack('<H', 1))            # PCM format
    buf.write(struct.pack('<H', num_channels))
    buf.write(struct.pack('<I', sample_rate))
    buf.write(struct.pack('<I', byte_rate))
    buf.write(struct.pack('<H', block_align))
    buf.write(struct.pack('<H', bits_per_sample))
    # data chunk
    buf.write(b'data')
    buf.write(struct.pack('<I', data_size))
    buf.write(pcm_data)

    return buf.getvalue()


def _resample_pcm(pcm_data: bytes, from_hz: int, to_hz: int) -> bytes:
    """
    Resample PCM 16bit little-endian de from_hz para to_hz.
    Usa interpolação linear via numpy (sem scipy).
    """
    if from_hz == to_hz:
        return pcm_data

    samples = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)
    num_out = int(len(samples) * to_hz / from_hz)
    x_old = np.linspace(0, 1, len(samples))
    x_new = np.linspace(0, 1, num_out)
    resampled = np.interp(x_new, x_old, samples)
    return resampled.astype(np.int16).tobytes()
