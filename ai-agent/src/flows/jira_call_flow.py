"""
jira_call_flow.py — Fluxo URA para abertura de chamado no Jira (Módulo 1)

Conduz a conversa com o cliente via TTS/STT usando Google Gemini,
coleta respostas para cada pergunta da URA e notifica o backend
para abrir o chamado no Jira ao final.

Mensagens de boas-vindas, informativa e encerramento são buscadas
dinamicamente do backend (/api/v1/ura/settings) — configuráveis
pela tela Fluxo URA sem necessidade de redeploy.

Orquestrador fino (fase 22, O3.3 da refatoração) — a captura/transcrição de áudio
mora em audio_capture.AudioCapture, a formatação de campos de fala em
speech_field_formatter, e a gravação/registro do chamado em call_recorder.CallRecorder.
"""

import asyncio
import logging
import time

import httpx

from src.flows.audio_capture import AudioCapture
from src.flows.call_recorder import CallRecorder
from src.flows.speech_field_formatter import build_stt_hint, matches_expected
from src.protocol import wait_playback_and_drain, write_audio_paced
from src.services.ai_service import AIService
from src.services.audio_cache import audio_cache as _audio_cache
from src.services import backend_client as bc

logger = logging.getLogger("asteriskia.flow.jira")

# Mensagens de fallback caso o backend esteja indisponível
_FALLBACK_BOAS_VINDAS  = "Bem-vindo ao sistema de atendimento. Como posso te ajudar?"
_FALLBACK_ENCERRAMENTO = "Seu chamado foi registrado. Em breve nossa equipe entrará em contato. Obrigado!"

# Taxa de amostragem do AudioSocket (8kHz, 16bit, mono)
_SAMPLE_RATE  = 8000
_BYTES_SAMPLE = 2

# Buffer pós-áudio: com write_audio_paced (pacing real-time), a função retorna
# ~junto com o fim da reprodução — 0.8s é suficiente para cobrir jitter do
# event loop e latência de rede interna Docker.
_POST_SPEAK_BUFFER_SECS = 0.8

# Segundos de silêncio contínuo para encerrar a captura (perguntas estruturadas)
_SILENCE_TIMEOUT_SECS = 2.0

# Duração máxima de captura em segundos (relógio real — garante encerramento independente de frame timing)
_MAX_CAPTURE_PERGUNTA = 12.0  # perguntas: até 12s de relógio


class JiraCallFlow:
    """
    Orquestrador do fluxo de URA para abertura de chamado Jira.

    Fluxo (sequencial e síncrono — sem paralelismo que cause race):
      1. Busca settings + perguntas do backend
      2. Fala boas-vindas → aguarda duração real do áudio + buffer
      3. Turno livre: ouve → transcreve → responde (LLM)
      4. Fala mensagem informativa (se preenchida)
      5. Para cada pergunta: fala → ouve → transcreve → armazena
      6. Fala confirmação → registra chamado → fala encerramento
    """

    # Perguntas com expected_values configurado (ex: "tipo de atendimento") têm até
    # essas tentativas para cair em uma opção válida — evita que ruído de STT vire
    # uma categoria "suja" nos indicadores (Ranking de Atendimentos agrupa por essa
    # resposta) quando o cliente falou algo fora do esperado.
    _MAX_TENTATIVAS_RESPOSTA_VALIDA = 2

    def __init__(
        self,
        call_uuid: str,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        caller_number: str = "desconhecido",
        ura_id: int = 1,
    ):
        self.call_uuid          = call_uuid
        self.reader             = reader
        self.writer             = writer
        self.caller_number      = caller_number
        self.ura_id             = ura_id
        self.ai                 = AIService()
        self._recorded_audio: list[bytes] = []   # PCM da chamada em ordem cronológica (URA + cliente) para gravação WAV
        self._call_start_time: float      = 0.0  # início do fluxo para cálculo de duração
        self.audio_capture = AudioCapture(call_uuid, reader, self.ai, self._recorded_audio)
        self.recorder      = CallRecorder(call_uuid, self.ai, caller_number, ura_id, self._recorded_audio)

    async def execute(self) -> None:
        self._call_start_time = time.monotonic()
        logger.info("[%s] Iniciando fluxo URA Jira", self.call_uuid)

        # 1. Busca settings e perguntas em paralelo para reduzir latência inicial
        settings, questions = await asyncio.gather(
            self._fetch_settings(),
            self._fetch_questions(),
        )

        boas_vindas  = settings.get("boas_vindas")  or _FALLBACK_BOAS_VINDAS
        informativa  = settings.get("informativa")  or ""
        encerramento = settings.get("encerramento") or _FALLBACK_ENCERRAMENTO

        # Sensibilidade do VAD é configurável na tela de Fluxo URA (0-3)
        self.audio_capture.set_aggressiveness(settings.get("vad_aggressiveness"))

        # 2. Boas-vindas — reproduz do cache (sem latência TTS)
        ok = await self._play_cached(boas_vindas)
        if not ok:
            return

        # 3. Mensagem informativa (opcional) — do cache se preenchida
        if informativa.strip():
            ok = await self._play_cached(informativa)
            if not ok:
                return

        # 4. Perguntas estruturadas — sequencial, uma por vez
        if not questions:
            await self._speak("Desculpe, não foi possível carregar as perguntas. Tente novamente.")
            return

        for question in questions:
            answer = await self._ask_question(
                question["question_text"],
                question.get("jiraFieldKey") or question.get("jira_field_key", ""),
                question.get("expectedValues") or question.get("expected_values", ""),
            )
            if answer:
                key = question.get("jiraFieldKey") or question.get("jira_field_key", "")
                self.recorder.collected_answers[key] = answer
                self.recorder.transcriptions.append(f"[{question['question_text']}]: {answer}")
                logger.info("[%s] %s = %r", self.call_uuid, key, answer)

        # 5. Confirmação — mensagem estática, serve do cache
        ok = await self._play_cached("Obrigado! Estou registrando seu chamado. Aguarde um momento.")
        if not ok:
            return

        # 6. Grava WAV com voz do chamador (MixMonitor não captura audio do AudioSocket)
        audio_path = f"/var/spool/asterisk/monitor/{self.call_uuid}.wav"
        try:
            self.recorder.write_wav(audio_path)
        except Exception as e:
            logger.warning("[%s] Erro ao salvar gravação: %s", self.call_uuid, e)
            audio_path = None

        # 7. Cria chamado no Jira via backend
        call_duration = int(time.monotonic() - self._call_start_time)
        issue_key = await self.recorder.create_jira_issue(audio_path=audio_path, duration_secs=call_duration)

        # 8. Encerramento
        if issue_key:
            spoken_key = CallRecorder.format_issue_key(issue_key)
            msg = encerramento.replace("{protocolo}", spoken_key)
        else:
            msg = encerramento.replace("{protocolo}", "").strip()
            if not msg:
                msg = "Atendimento registrado. Nossa equipe entrará em contato. Obrigado!"

        await self._speak(msg)
        logger.info("[%s] Fluxo concluído | Jira: %s", self.call_uuid, issue_key)

    # ─── helpers ─────────────────────────────────────────────────────────────

    async def _speak(self, text: str) -> bool:
        """
        Gera TTS e envia ao Asterisk em streaming real-time.

        Com write_audio_paced, a função retorna ~junto com o fim da reprodução.
        Ao final, drena frames acumulados no reader durante o TTS.
        """
        if self.writer.is_closing():
            return False
        # Texto vazio causaria erro 400 no Gemini TTS
        if not text or not text.strip():
            logger.warning("[%s] _speak ignorado — texto vazio", self.call_uuid)
            return True
        try:
            logger.debug("[%s] TTS: %r", self.call_uuid, text[:80])

            t_start = time.monotonic()
            ok, duration = await self.ai.synthesize_speech_streaming(text, self.writer, record=self._recorded_audio)
            elapsed = time.monotonic() - t_start

            if not ok:
                logger.warning("[%s] Conexão encerrada durante TTS", self.call_uuid)
                return False

            # Com pacing real-time, elapsed ≈ TTFT + duration.
            logger.debug(
                "[%s] Áudio: %.1fs | elapsed: %.1fs",
                self.call_uuid, duration, elapsed,
            )
            return await wait_playback_and_drain(
                self.reader, duration, elapsed, _POST_SPEAK_BUFFER_SECS, self.call_uuid
            )
        except (BrokenPipeError, ConnectionResetError):
            logger.warning("[%s] Pipe quebrado durante TTS", self.call_uuid)
            return False
        except Exception as e:
            logger.error("[%s] Erro TTS: %s", self.call_uuid, e)
            return False

    async def _play_cached(self, text: str) -> bool:
        """
        Reproduz mensagem via PCM pré-gerado em disco — sem chamada TTS em tempo real.

        Zero latência de TTS: o PCM é lido do cache local.
        Fallback automático para _speak() se o cache não estiver disponível.
        """
        if not text or not text.strip():
            logger.warning("[%s] _play_cached ignorado — texto vazio", self.call_uuid)
            return True

        pcm = await _audio_cache.get_or_generate(text, self.ai)
        if not pcm:
            logger.warning("[%s] Cache indisponível — usando TTS em tempo real", self.call_uuid)
            return await self._speak(text)

        if self.writer.is_closing():
            return False

        try:
            duration = len(pcm) / (_SAMPLE_RATE * _BYTES_SAMPLE)
            logger.debug(
                "[%s] Reproduzindo cache PCM: %.1fs (%d bytes)",
                self.call_uuid, duration, len(pcm),
            )
            t_start = time.monotonic()
            ok = await write_audio_paced(self.writer, pcm, record=self._recorded_audio)
            elapsed = time.monotonic() - t_start

            if not ok:
                logger.warning("[%s] Conexão encerrada durante reprodução do cache", self.call_uuid)
                return False

            return await wait_playback_and_drain(
                self.reader, duration, elapsed, _POST_SPEAK_BUFFER_SECS, self.call_uuid
            )
        except (BrokenPipeError, ConnectionResetError):
            logger.warning("[%s] Pipe quebrado durante reprodução do cache", self.call_uuid)
            return False
        except Exception as e:
            logger.error("[%s] Erro na reprodução do cache: %s — usando TTS em tempo real", self.call_uuid, e)
            return await self._speak(text)

    async def _ask_question(self, question_text: str, field_key: str = "", expected_values: str = "") -> str | None:
        """
        Reproduz a pergunta (do cache) e captura/transcreve a resposta do cliente.

        Quando a pergunta tem expected_values configurado, uma resposta que não bate
        com nenhuma opção é tratada como não reconhecida: repergunta até
        _MAX_TENTATIVAS_RESPOSTA_VALIDA vezes e, se ainda assim não validar, descarta
        a resposta (retorna None) em vez de gravar texto livre/ruído como se fosse
        uma categoria válida.
        """
        hint = build_stt_hint(question_text, field_key, expected_values)

        ok = await self._play_cached(question_text)
        if not ok:
            return None
        answer = await self.audio_capture.listen_and_transcribe(
            silence_timeout=_SILENCE_TIMEOUT_SECS,
            max_duration=_MAX_CAPTURE_PERGUNTA,
            hint=hint,
            field_key=field_key,
            expected_values=expected_values,
        )

        if not expected_values:
            return answer

        tentativas = 1
        while (not answer or not matches_expected(answer, expected_values)) \
                and tentativas < self._MAX_TENTATIVAS_RESPOSTA_VALIDA:
            logger.info("[%s] Resposta '%s' fora das opções esperadas (%s) — repergunta (%d/%d)",
                        self.call_uuid, answer, expected_values, tentativas + 1, self._MAX_TENTATIVAS_RESPOSTA_VALIDA)
            ok = await self._play_cached(
                f"Não entendi. Por favor, responda com uma destas opções: {expected_values}."
            )
            if not ok:
                return None
            answer = await self.audio_capture.listen_and_transcribe(
                silence_timeout=_SILENCE_TIMEOUT_SECS,
                max_duration=_MAX_CAPTURE_PERGUNTA,
                hint=hint,
                field_key=field_key,
                expected_values=expected_values,
            )
            tentativas += 1

        if answer and matches_expected(answer, expected_values):
            return answer

        logger.warning("[%s] Nenhuma resposta válida para '%s' após %d tentativa(s) — descartando (era: %r)",
                        self.call_uuid, question_text, tentativas, answer)
        return None

    async def _fetch_settings(self) -> dict[str, str]:
        try:
            items: list[dict] = await bc.get(f"/api/v1/uras/{self.ura_id}/settings")
            return {item["key"]: item["value"] for item in items}
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 404:
                logger.info("[%s] URA %s sem settings cadastradas", self.call_uuid, self.ura_id)
            else:
                logger.error("[%s] Erro HTTP ao buscar settings URA: %s", self.call_uuid, e)
            return {}
        except Exception as e:
            logger.error("[%s] Erro ao buscar settings URA: %s", self.call_uuid, e)
            return {}

    async def _fetch_questions(self) -> list[dict]:
        try:
            result = await bc.get(f"/api/v1/uras/{self.ura_id}/questions")
            if not isinstance(result, list):
                logger.error(
                    "[%s] Backend retornou formato inesperado para perguntas: %s",
                    self.call_uuid, type(result).__name__,
                )
                return []
            return result
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 404:
                logger.info("[%s] URA %s sem perguntas cadastradas", self.call_uuid, self.ura_id)
            else:
                logger.error("[%s] Erro HTTP ao buscar perguntas URA: %s", self.call_uuid, e)
            return []
        except Exception as e:
            logger.error("[%s] Erro ao buscar perguntas URA: %s", self.call_uuid, e)
            return []
