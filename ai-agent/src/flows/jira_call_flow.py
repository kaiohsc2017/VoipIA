"""
jira_call_flow.py — Fluxo URA para abertura de chamado no Jira (Módulo 1)

Conduz a conversa com o cliente via TTS/STT usando Google Gemini,
coleta respostas para cada pergunta da URA e notifica o backend
para abrir o chamado no Jira ao final.

Mensagens de boas-vindas, informativa e encerramento são buscadas
dinamicamente do backend (/api/v1/ura/settings) — configuráveis
pela tela Fluxo URA sem necessidade de redeploy.
"""

import asyncio
import logging
from src.protocol import read_frame, write_audio
from src.services.ai_service import AIService
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

# Threshold RMS abaixo do qual o frame é considerado silêncio
_SILENCE_THRESHOLD = 300

# Segundos de silêncio contínuo para encerrar a captura
_SILENCE_TIMEOUT_SECS = 1.8


def _trim_silence(pcm: bytes, threshold: int = 300, frame_size: int = 320) -> bytes:
    """
    Remove frames de silêncio do início e fim do PCM.
    Reduz o tamanho do áudio enviado ao STT — menos dados = resposta mais rápida.
    """
    import struct
    frames = [pcm[i:i+frame_size] for i in range(0, len(pcm), frame_size) if len(pcm[i:i+frame_size]) == frame_size]
    if not frames:
        return pcm

    def is_silent(frame: bytes) -> bool:
        samples = struct.unpack(f"<{len(frame)//2}h", frame)
        rms = (sum(x*x for x in samples) / len(samples)) ** 0.5
        return rms < threshold

    # Encontra primeiro frame com voz
    start = 0
    for i, f in enumerate(frames):
        if not is_silent(f):
            start = max(0, i - 2)  # mantém 2 frames antes para contexto
            break

    # Encontra último frame com voz
    end = len(frames)
    for i in range(len(frames) - 1, -1, -1):
        if not is_silent(frames[i]):
            end = min(len(frames), i + 3)  # mantém 3 frames depois
            break

    return b"".join(frames[start:end])


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

    def __init__(
        self,
        call_uuid: str,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        caller_number: str = "desconhecido",
    ):
        self.call_uuid     = call_uuid
        self.reader        = reader
        self.writer        = writer
        self.caller_number = caller_number
        self.ai            = AIService()
        self.collected_answers: dict[str, str] = {}
        self._transcriptions: list[str]        = []

    async def execute(self) -> None:
        logger.info("[%s] Iniciando fluxo URA Jira", self.call_uuid)

        # 1. Busca settings e perguntas (sequencial — simples e seguro)
        settings  = await self._fetch_settings()
        questions = await self._fetch_questions()

        boas_vindas  = settings.get("boas_vindas")  or _FALLBACK_BOAS_VINDAS
        informativa  = settings.get("informativa")  or ""
        encerramento = settings.get("encerramento") or _FALLBACK_ENCERRAMENTO

        # 2. Boas-vindas — aguarda o áudio terminar completamente
        ok = await self._speak(boas_vindas)
        if not ok:
            return

        # 3. Turno livre — ouve o cliente e responde com LLM
        user_text = await self._listen_and_transcribe()
        if user_text:
            self._transcriptions.append(f"Cliente: {user_text}")
            system_prompt = (
                "Você é uma assistente virtual de atendimento ao cliente. "
                "Responda de forma breve e clara em português do Brasil. "
                "Ao abrir um protocolo, confirme o número gerado para o cliente."
            )
            response = await self.ai.generate_response_with_tools(
                system_instruction=system_prompt,
                history=[{"role": "user", "text": user_text}],
            )
            if response:
                self.collected_answers["description"] = user_text
                ok = await self._speak(response)
                if not ok:
                    return

        # 4. Mensagem informativa (opcional)
        if informativa.strip():
            ok = await self._speak(informativa)
            if not ok:
                return

        # 5. Perguntas estruturadas — sequencial, uma por vez
        if not questions:
            await self._speak("Desculpe, não foi possível carregar as perguntas. Tente novamente.")
            return

        for question in questions:
            answer = await self._ask_question(question["question_text"])
            if answer:
                key = question["jira_field_key"]
                self.collected_answers[key] = answer
                self._transcriptions.append(f"[{question['question_text']}]: {answer}")
                logger.info("[%s] %s = %r", self.call_uuid, key, answer)

        # 6. Confirmação
        ok = await self._speak("Obrigado! Estou registrando seu chamado. Aguarde um momento.")
        if not ok:
            return

        # 7. Cria chamado no Jira via backend
        issue_key = await self._create_jira_issue()

        # 8. Encerramento
        if issue_key:
            spoken_key = self._format_issue_key(issue_key)
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

        O provider gerencia keep_alive internamente — não é necessário aqui.
        Com write_audio_paced, a função retorna ~junto com o fim da reprodução,
        então elapsed ≈ TTFT + duration e o tempo de espera residual é mínimo.
        Ao final, drena frames acumulados no reader durante o TTS para evitar
        que ruído/silêncio do período de espera seja transcrito como resposta.
        """
        import time
        if self.writer.is_closing():
            return False
        try:
            logger.debug("[%s] TTS: %r", self.call_uuid, text[:80])

            t_start = time.monotonic()
            ok, duration = await self.ai.synthesize_speech_streaming(text, self.writer)
            elapsed = time.monotonic() - t_start

            if not ok:
                logger.warning("[%s] Conexão encerrada durante TTS", self.call_uuid)
                return False

            # Com pacing real-time, elapsed ≈ TTFT + duration.
            # Aguarda somente o áudio residual (se elapsed < duration) + buffer.
            remaining = max(0.0, duration - elapsed) + _POST_SPEAK_BUFFER_SECS
            logger.debug(
                "[%s] Áudio: %.1fs | elapsed: %.1fs | aguardando: %.1fs",
                self.call_uuid, duration, elapsed, remaining,
            )
            await asyncio.sleep(remaining)

            # Drena frames acumulados durante TTS para evitar dessincronismo
            hangup = await self._drain_reader()
            if hangup:
                return False
            return True
        except (BrokenPipeError, ConnectionResetError):
            logger.warning("[%s] Pipe quebrado durante TTS", self.call_uuid)
            return False
        except Exception as e:
            logger.error("[%s] Erro TTS: %s", self.call_uuid, e)
            return False

    async def _drain_reader(self) -> bool:
        """
        Descarta frames acumulados no reader durante geração/reprodução do TTS.

        O Asterisk envia áudio do microfone continuamente — mesmo quando o
        cliente está escutando a URA. Esses frames precisam ser descartados
        antes de iniciar a captura real para que o STT não transcreva ruído
        de fundo capturado durante a fala da URA como resposta do cliente.

        Timeout 50ms: esvazia o buffer já preenchido (frames buffered chegam
        em <1ms), e é pequeno o suficiente para não cortar resposta do cliente
        (o cliente tipicamente demora >500ms para falar após ouvir a pergunta).

        Retorna True se detectou hangup durante a drenagem.
        """
        drained = 0
        try:
            while True:
                frame = await asyncio.wait_for(read_frame(self.reader), timeout=0.05)
                if frame is None or frame.is_hangup:
                    return True  # hangup detectado
                drained += 1
        except asyncio.TimeoutError:
            pass
        if drained:
            logger.debug("[%s] Drenados %d frames stale pós-TTS", self.call_uuid, drained)
        return False

    # Padrões que indicam que o STT captou ruído/TTS em vez de voz humana real
    _NOISE_PATTERNS = (
        '[música', '[music', '[ruído', '[noise', '[silêncio', '[silence',
        '[sem fala', 'sem fala', 'não há fala', 'barulho de máquina',
        'música instrumental', '[audio', '[som', 'background',
    )

    async def _listen_and_transcribe(self) -> str | None:
        """Captura áudio do cliente, remove silêncio e transcreve via STT."""
        audio = await self._capture_audio()
        if not audio:
            return None
        audio = _trim_silence(audio)
        if len(audio) < 320 * 10:  # menos de 10 frames (~0.2s) → sem voz real
            return None
        try:
            text = await self.ai.transcribe(audio)
            if not text:
                return None
            # Filtra transcrições de ruído/música — o STT captou o áudio da URA
            text_lower = text.lower().strip()
            if any(p in text_lower for p in self._NOISE_PATTERNS):
                logger.debug("[%s] STT filtrou ruído: %r", self.call_uuid, text)
                return None
            logger.info("[%s] STT: %r", self.call_uuid, text)
            return text
        except Exception as e:
            logger.error("[%s] Erro STT: %s", self.call_uuid, e)
            return None

    async def _ask_question(self, question_text: str) -> str | None:
        """Fala a pergunta e captura/transcreve a resposta do cliente."""
        ok = await self._speak(question_text)
        if not ok:
            return None
        return await self._listen_and_transcribe()

    async def _capture_audio(self) -> bytes:
        """
        Captura frames de áudio do cliente até detectar silêncio contínuo
        por _SILENCE_TIMEOUT_SECS ou atingir max_duration.
        """
        import struct as _struct

        audio_chunks: list[bytes] = []
        frame_duration  = 320 / (_SAMPLE_RATE * _BYTES_SAMPLE)  # 0.02s por frame
        silence_limit   = int(_SILENCE_TIMEOUT_SECS / frame_duration)
        max_frames      = int(30.0 / frame_duration)  # máximo 30s

        silence_count = 0

        for _ in range(max_frames):
            try:
                frame = await asyncio.wait_for(read_frame(self.reader), timeout=8.0)
            except asyncio.TimeoutError:
                logger.debug("[%s] Timeout na leitura de frame — encerrando captura", self.call_uuid)
                break

            if frame is None or frame.is_hangup:
                break
            if not frame.is_audio:
                continue

            payload = frame.payload
            audio_chunks.append(payload)

            # RMS para detecção de silêncio
            samples = _struct.unpack(f"<{len(payload)//2}h", payload)
            rms = (sum(x * x for x in samples) / len(samples)) ** 0.5

            if rms < _SILENCE_THRESHOLD:
                silence_count += 1
                if silence_count >= silence_limit:
                    logger.debug("[%s] Silêncio detectado após %.1fs", self.call_uuid, len(audio_chunks) * frame_duration)
                    break
            else:
                silence_count = 0

        return b"".join(audio_chunks)

    async def _fetch_settings(self) -> dict[str, str]:
        try:
            items: list[dict] = await bc.get("/api/v1/ura/settings")
            return {item["key"]: item["value"] for item in items}
        except Exception as e:
            logger.error("[%s] Erro ao buscar settings URA: %s", self.call_uuid, e)
            return {}

    async def _fetch_questions(self) -> list[dict]:
        try:
            result = await bc.get("/api/v1/ura/questions")
            if not isinstance(result, list):
                logger.error(
                    "[%s] Backend retornou formato inesperado para perguntas: %s",
                    self.call_uuid, type(result).__name__,
                )
                return []
            return result
        except Exception as e:
            logger.error("[%s] Erro ao buscar perguntas URA: %s", self.call_uuid, e)
            return []

    async def _create_jira_issue(self) -> str | None:
        try:
            full_transcription = "\n".join(self._transcriptions)
            self.collected_answers.setdefault("description", full_transcription)
            if self.caller_number and self.caller_number != "desconhecido":
                self.collected_answers.setdefault("customfield_telefone", self.caller_number)

            payload = {
                "callUuid":      self.call_uuid,
                "fields":        self.collected_answers,
                "audioFilePath": f"/var/spool/asterisk/monitor/{self.call_uuid}.wav",
                "transcription": full_transcription,
                "callerNumber":  self.caller_number,
            }
            data = await bc.post("/api/v1/calls/register", json=payload)
            return data.get("jiraIssueKey")
        except Exception as e:
            logger.error("[%s] Erro ao registrar chamado: %s", self.call_uuid, e)
            return None

    @staticmethod
    def _format_issue_key(key: str) -> str:
        parts = key.split("-")
        if len(parts) == 2:
            return f"{' '.join(parts[0])}, {parts[1]}"
        return key
