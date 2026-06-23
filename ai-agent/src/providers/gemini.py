"""
providers/gemini.py — Provedor Google Gemini.

USA o model_id recebido do banco (ai_capability_chain) — não o .env.
Cada capability (STT, LLM, TTS) tem seu próprio model_id vindo do banco.
"""
from __future__ import annotations

import asyncio
import io
import logging
import wave

from src.providers.base import BaseAIProvider, ProviderError

logger = logging.getLogger("asteriskia.provider.gemini")


def _client():
    from src.services.gemini_service import _get_global_client
    return _get_global_client()


def _clean_for_tts(text: str) -> str:
    """Remove markdown e prefixos do LLM que causam erro 400 no TTS."""
    import re
    t = str(text).strip()
    t = re.sub(r'\*+', '', t)
    t = re.sub(r'_+([^_]+)_+', r'', t)
    t = re.sub(r'`[^`]+`', '', t)
    for prefix in ('Resultado:', 'Resposta:', 'Assistente:', 'AI:', 'Bot:'):
        if t.startswith(prefix):
            t = t[len(prefix):].strip()
    return t or str(text)


class GeminiProvider(BaseAIProvider):

    def __init__(self, model_id: str):
        self._model_id = model_id

    @property
    def provider_id(self) -> str:
        return "gemini"

    @property
    def model_id(self) -> str:
        return self._model_id

    # ── STT ──────────────────────────────────────────────────────────────────

    async def transcribe(self, pcm_data: bytes) -> str:
        try:
            return await asyncio.to_thread(self._transcribe_sync, pcm_data)
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e

    def _transcribe_sync(self, pcm_data: bytes) -> str:
        from google.genai import types as t

        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(8000)
            wf.writeframes(pcm_data)

        resp = _client().models.generate_content(
            model=self._model_id,
            contents=[t.Content(parts=[
                t.Part(text="Transcreva em português do Brasil. Apenas o texto:"),
                t.Part(inline_data=t.Blob(mime_type="audio/wav", data=buf.getvalue())),
            ])],
        )
        return resp.text or ""

    # ── LLM ──────────────────────────────────────────────────────────────────

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        try:
            return await asyncio.to_thread(
                self._llm_sync, system_instruction, history
            )
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e

    def _llm_sync(self, system_instruction: str, history: list[dict]) -> str:
        from google.genai import types as t
        from src.services.gemini_service import _execute_tool

        config = t.GenerateContentConfig(
            system_instruction=system_instruction,
            tools=[t.Tool(function_declarations=[
                t.FunctionDeclaration(
                    name="create_jira_issue",
                    description="Cria chamado no Jira com os dados coletados",
                    parameters=t.Schema(
                        type="OBJECT",
                        properties={
                            "summary":     t.Schema(type="STRING"),
                            "description": t.Schema(type="STRING"),
                            "priority":    t.Schema(type="STRING"),
                        },
                        required=["summary"],
                    ),
                )
            ])],
        )

        contents = [
            t.Content(
                role="model" if h.get("role") in ("model", "assistant") else "user",
                parts=[t.Part(text=h.get("text") or h.get("content") or "")],
            )
            for h in history
        ]

        resp = _client().models.generate_content(
            model=self._model_id,
            contents=contents,
            config=config,
        )

        for _ in range(3):
            part = resp.candidates[0].content.parts[0]
            if not (hasattr(part, "function_call") and part.function_call):
                break
            fc = part.function_call
            result = _execute_tool(fc.name, dict(fc.args))
            contents += [
                resp.candidates[0].content,
                t.Content(role="user", parts=[t.Part(
                    function_response=t.FunctionResponse(
                        name=fc.name, response={"result": result}
                    )
                )]),
            ]
            resp = _client().models.generate_content(
                model=self._model_id, contents=contents, config=config
            )

        return resp.text or ""

    # ── TTS ──────────────────────────────────────────────────────────────────

    async def synthesize_speech_streaming(self, text: str, writer) -> tuple[bool, float]:
        try:
            pcm = await asyncio.to_thread(self._tts_sync, str(text))
            if not pcm:
                return True, 0.0
            from src.protocol import write_audio
            sent = await write_audio(writer, pcm)
            return sent, len(pcm) / (8000 * 2)
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e

    async def synthesize_speech(self, text: str) -> bytes:
        try:
            return await asyncio.to_thread(self._tts_sync, str(text))
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e

    def _tts_sync(self, text: str) -> bytes:
        from google.genai import types as t
        from src.services.gemini_service import _resample_pcm

        chunks: list[bytes] = []
        for chunk in _client().models.generate_content_stream(
            model=self._model_id,
            contents=_clean_for_tts(str(text)),  # texto limpo — sem markdown/roles
            config=t.GenerateContentConfig(
                response_modalities=["AUDIO"],
                speech_config=t.SpeechConfig(
                    voice_config=t.VoiceConfig(
                        prebuilt_voice_config=t.PrebuiltVoiceConfig(voice_name="Aoede")
                    )
                ),
            ),
        ):
            try:
                data = chunk.candidates[0].content.parts[0].inline_data.data
                if data:
                    chunks.append(data)
            except (IndexError, AttributeError):
                continue

        if not chunks:
            return b""
        return _resample_pcm(b"".join(chunks), from_hz=24000, to_hz=8000)
