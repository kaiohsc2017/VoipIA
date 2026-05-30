"""
gemini_service.py — Serviço de IA via Google Gemini
Encapsula STT (Speech-to-Text), LLM e TTS (Text-to-Speech).

Todos os métodos trabalham com áudio no formato:
  - Entrada (STT): PCM 8kHz 16bit signed little-endian mono (formato Asterisk)
  - Saída (TTS): PCM 8kHz 16bit signed little-endian mono (formato Asterisk)
"""

import io
import logging
import asyncio
import numpy as np
import soundfile as sf
import google.generativeai as genai
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
        genai.configure(api_key=GEMINI_API_KEY)
        self._stt_model = genai.GenerativeModel(GEMINI_MODEL_STT)
        self._llm_model = genai.GenerativeModel(GEMINI_MODEL_LLM)
        self._tts_model = genai.GenerativeModel(GEMINI_MODEL_TTS)

    async def transcribe(self, pcm_data: bytes) -> str:
        """
        Converte áudio PCM (formato Asterisk) em texto via Gemini STT.

        Args:
            pcm_data: Áudio bruto PCM 8kHz/16bit/mono do Asterisk

        Returns:
            Texto transcrito ou string vazia em caso de erro
        """
        try:
            # Converte PCM bruto para WAV (formato aceito pelo Gemini)
            wav_bytes = self._pcm_to_wav(pcm_data, sample_rate=8000)

            # Executa STT em thread separada para não bloquear o event loop
            result = await asyncio.to_thread(
                self._call_stt_api,
                wav_bytes
            )
            return result.strip()
        except Exception as e:
            logger.error(f"Erro no STT: {e}")
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
            # Executa TTS em thread separada para não bloquear o event loop
            audio_wav = await asyncio.to_thread(
                self._call_tts_api,
                text
            )
            # Converte WAV resultante para PCM 8kHz (formato Asterisk)
            return self._wav_to_pcm8k(audio_wav)
        except Exception as e:
            logger.error(f"Erro no TTS: {e}")
            # Retorna silêncio em caso de erro (320 bytes = 20ms)
            return b'\x00' * 320

    async def generate_response(self, prompt: str, context: str = "") -> str:
        """
        Gera uma resposta de texto via Gemini LLM.
        Usado para interpretar respostas do usuário e mapear
        aos campos do Jira.

        Args:
            prompt: Instrução para o modelo
            context: Contexto adicional (histórico da conversa, etc.)

        Returns:
            Resposta gerada pelo LLM
        """
        try:
            full_prompt = f"{context}\n\n{prompt}" if context else prompt
            result = await asyncio.to_thread(
                self._llm_model.generate_content,
                full_prompt
            )
            return result.text.strip()
        except Exception as e:
            logger.error(f"Erro no LLM: {e}")
            return ""

    # ------------------------------------------------------------------
    # Métodos privados — Conversão de formatos de áudio
    # ------------------------------------------------------------------

    def _pcm_to_wav(self, pcm_data: bytes, sample_rate: int = 8000) -> bytes:
        """
        Converte áudio PCM 16bit little-endian para WAV em memória.

        Args:
            pcm_data: Bytes PCM brutos
            sample_rate: Taxa de amostragem (padrão Asterisk = 8000Hz)

        Returns:
            Bytes do arquivo WAV
        """
        # Interpreta bytes como array de inteiros 16bit signed
        samples = np.frombuffer(pcm_data, dtype=np.int16)
        # Normaliza para float32 [-1.0, 1.0]
        float_samples = samples.astype(np.float32) / 32768.0

        buffer = io.BytesIO()
        sf.write(buffer, float_samples, sample_rate, format='WAV', subtype='PCM_16')
        buffer.seek(0)
        return buffer.read()

    def _wav_to_pcm8k(self, wav_bytes: bytes) -> bytes:
        """
        Converte WAV para PCM 8kHz/16bit/mono (formato Asterisk).

        Args:
            wav_bytes: Conteúdo do arquivo WAV

        Returns:
            Bytes PCM 8kHz/16bit/mono
        """
        buffer = io.BytesIO(wav_bytes)
        data, sample_rate = sf.read(buffer, dtype='float32')

        # Converte para mono se necessário
        if data.ndim > 1:
            data = data.mean(axis=1)

        # Resample para 8kHz se necessário
        if sample_rate != 8000:
            from scipy.signal import resample
            num_samples = int(len(data) * 8000 / sample_rate)
            data = resample(data, num_samples)

        # Converte de float32 para int16 (PCM 16bit)
        pcm_int16 = (data * 32767).clip(-32768, 32767).astype(np.int16)
        return pcm_int16.tobytes()

    def _call_stt_api(self, wav_bytes: bytes) -> str:
        """Chamada síncrona ao Gemini STT (executada em thread separada)."""
        import tempfile, os
        # Salva WAV temporariamente para upload ao Gemini
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(wav_bytes)
            tmp_path = f.name

        try:
            audio_file = genai.upload_file(tmp_path, mime_type="audio/wav")
            response = self._stt_model.generate_content([
                "Transcreva exatamente o que foi dito neste áudio em português do Brasil. "
                "Retorne apenas o texto transcrito, sem pontuação adicional.",
                audio_file
            ])
            return response.text
        finally:
            os.unlink(tmp_path)

    def _call_tts_api(self, text: str) -> bytes:
        """
        Chamada síncrona ao Gemini TTS (executada em thread separada).
        Retorna áudio em formato WAV.
        """
        response = self._tts_model.generate_content(
            text,
            generation_config=genai.GenerationConfig(
                response_modalities=["AUDIO"],
                speech_config=genai.SpeechConfig(
                    voice_config=genai.VoiceConfig(
                        prebuilt_voice_config=genai.PrebuiltVoiceConfig(
                            voice_name="Aoede"  # Voz feminina natural em PT-BR
                        )
                    )
                )
            )
        )
        # Extrai bytes de áudio da resposta
        audio_data = response.candidates[0].content.parts[0].inline_data.data
        return audio_data
