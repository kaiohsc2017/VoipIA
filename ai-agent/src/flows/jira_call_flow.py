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
from src.protocol import read_frame, write_audio, keep_alive_silence
from src.services.gemini_service import GeminiService
from src.services import backend_client as bc

logger = logging.getLogger("asteriskia.flow.jira")

# Mensagens de fallback caso o backend esteja indisponível
_FALLBACK_BOAS_VINDAS  = "Bem-vindo ao sistema de atendimento. Como posso te ajudar?"
_FALLBACK_ENCERRAMENTO = "Seu chamado foi registrado. Em breve nossa equipe entrará em contato. Obrigado!"


class JiraCallFlow:
    """
    Orquestrador do fluxo de URA para abertura de chamado Jira.

    Responsabilidades:
      1. Buscar mensagens e perguntas ativas da URA no backend
      2. Reproduzir boas-vindas → informativa (se preenchida) → perguntas
      3. Acionar o backend para criar o chamado no Jira
      4. Confirmar o número do chamado ao cliente via mensagem de encerramento
    """

    def __init__(
        self,
        call_uuid: str,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter
    ):
        self.call_uuid = call_uuid
        self.reader = reader
        self.writer = writer
        self.gemini = GeminiService()
        self.collected_answers: dict[str, str] = {}

    async def execute(self) -> None:
        """Executa o fluxo completo da URA."""
        logger.info(f"[{self.call_uuid}] Iniciando fluxo URA Jira")

        # 1. Busca mensagens configuráveis do backend
        settings = await self._fetch_settings()
        boas_vindas  = settings.get("boas_vindas")  or _FALLBACK_BOAS_VINDAS
        informativa  = settings.get("informativa")  or ""
        encerramento = settings.get("encerramento") or _FALLBACK_ENCERRAMENTO

        # 2. Boas-vindas (obrigatória)
        await self._speak(boas_vindas)

        # 3. Turno conversacional livre com Function Calling
        user_audio = await self._capture_audio(silence_timeout=4.0, max_duration=20.0)
        if user_audio:
            user_text = await self.gemini.transcribe(user_audio)
            if user_text:
                logger.info(f"[{self.call_uuid}] Cliente disse: '{user_text}'")

                system_prompt = (
                    "Você é uma assistente virtual de atendimento ao cliente do sistema AsteriskIA. "
                    "Responda de forma breve, clara e em português do Brasil. "
                    "Quando o cliente solicitar informações sobre um pedido, use a função disponibilizada. "
                    "Ao abrir um protocolo, confirme o número gerado para o cliente."
                )

                response_text = await self.gemini.generate_response_with_tools(
                    system_instruction=system_prompt,
                    history=[{"role": "user", "text": user_text}],
                )

                if response_text:
                    await self._speak(response_text)
                    self.collected_answers["description"] = user_text

        # 4. Mensagem informativa (opcional — só fala se tiver conteúdo)
        if informativa.strip():
            await self._speak(informativa)

        # 5. Busca perguntas estruturadas da URA
        questions = await self._fetch_questions()
        if not questions:
            await self._speak("Desculpe, ocorreu um erro ao carregar as perguntas. Tente novamente.")
            return

        # 6. Para cada pergunta: fala → ouve → transcreve → armazena
        for question in questions:
            answer = await self._ask_question(question["question_text"])
            if answer:
                self.collected_answers[question["jira_field_key"]] = answer
                logger.info(f"[{self.call_uuid}] Campo '{question['jira_field_key']}' = '{answer}'")

        # 7. Confirmação antes de abrir
        await self._speak("Obrigado! Estou registrando seu chamado. Por favor, aguarde um momento.")

        # 8. Notifica backend para criar o chamado
        issue_key = await self._create_jira_issue()

        # 9. Mensagem de encerramento — substitui {protocolo} pelo número real
        if issue_key:
            spoken_key = self._speak_formatted_key(issue_key)
            msg = encerramento.replace("{protocolo}", spoken_key)
        else:
            # Remove placeholder se não houver issue_key
            msg = encerramento.replace("{protocolo}", "").replace("  ", " ").strip()
            if not msg:
                msg = "Seu atendimento foi registrado. Nossa equipe entrará em contato. Obrigado!"

        await self._speak(msg)
        logger.info(f"[{self.call_uuid}] Fluxo URA Jira concluído | Chamado: {issue_key}")

    # ─── helpers ─────────────────────────────────────────────────────────────

    async def _ask_question(self, question_text: str) -> str | None:
        """Fala a pergunta via TTS e captura/transcreve a resposta do cliente."""
        await self._speak(question_text)
        audio_buffer = await self._capture_audio(silence_timeout=3.0, max_duration=15.0)
        if not audio_buffer:
            return None
        transcription = await self.gemini.transcribe(audio_buffer)
        logger.debug(f"[{self.call_uuid}] STT resultado: '{transcription}'")
        return transcription

    async def _speak(self, text: str) -> bool:
        """
        Converte texto em áudio (TTS) e envia ao Asterisk.

        Mantém o AudioSocket vivo durante a geração do TTS (pode levar 5-10s)
        enviando frames de silêncio em paralelo. Sem isso, o Asterisk encerra
        a chamada por timeout de inatividade.

        Returns:
            True se enviado com sucesso, False se a conexão foi encerrada.
        """
        if self.writer.is_closing():
            return False
        try:
            # Inicia keep-alive de silêncio enquanto o Gemini gera o TTS
            stop_silence = asyncio.Event()
            silence_task = asyncio.create_task(
                keep_alive_silence(self.writer, stop_silence)
            )
            try:
                audio_pcm = await self.gemini.synthesize_speech(text)
            finally:
                stop_silence.set()
                await silence_task

            sent = await write_audio(self.writer, audio_pcm)
            if not sent:
                logger.warning(f"[{self.call_uuid}] Conexão encerrada durante TTS — abortando fala.")
                return False
            words = len(text.split())
            estimated_secs = max(1.0, words / 3.0)
            await asyncio.sleep(estimated_secs)
            return True
        except (BrokenPipeError, ConnectionResetError):
            logger.warning(f"[{self.call_uuid}] Pipe quebrado durante TTS — cliente desligou.")
            return False
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro TTS: {e}")
            return False

    async def _capture_audio(
        self,
        silence_timeout: float = 3.0,
        max_duration: float = 15.0
    ) -> bytes:
        """Captura áudio do cliente até detectar silêncio ou atingir o tempo máximo."""
        audio_chunks: list[bytes] = []
        SILENCE_THRESHOLD = 300
        SAMPLE_RATE = 8000
        FRAME_BYTES = 320

        silence_frames = 0
        max_frames     = int(max_duration  * (SAMPLE_RATE / (FRAME_BYTES // 2)))
        silence_limit  = int(silence_timeout * (SAMPLE_RATE / (FRAME_BYTES // 2)))

        for _ in range(max_frames):
            frame = await asyncio.wait_for(read_frame(self.reader), timeout=5.0)
            if frame is None or frame.is_hangup:
                break
            if not frame.is_audio:
                continue

            audio_chunks.append(frame.payload)

            import struct as s
            samples = s.unpack(f"<{len(frame.payload)//2}h", frame.payload)
            rms = (sum(x**2 for x in samples) / len(samples)) ** 0.5
            if rms < SILENCE_THRESHOLD:
                silence_frames += 1
                if silence_frames >= silence_limit:
                    break
            else:
                silence_frames = 0

        return b"".join(audio_chunks)

    async def _fetch_settings(self) -> dict[str, str]:
        """
        Busca as mensagens configuráveis da URA no backend.
        Retorna dict { key: value } — ex: { 'boas_vindas': '...', 'informativa': '', 'encerramento': '...' }
        """
        try:
            items: list[dict] = await bc.get("/api/v1/ura/settings")
            return {item["key"]: item["value"] for item in items}
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao buscar ura_settings, usando fallback: {e}")
            return {}

    async def _fetch_questions(self) -> list[dict]:
        """Busca perguntas ativas da URA no backend (autenticado)."""
        try:
            return await bc.get("/api/v1/ura/questions")
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao buscar perguntas URA: {e}")
            return []

    async def _create_jira_issue(self) -> str | None:
        """Envia dados coletados ao backend para criação do chamado no Jira."""
        try:
            payload = {"callUuid": self.call_uuid, "fields": self.collected_answers}
            data = await bc.post("/api/v1/calls/register", json=payload)
            return data.get("jiraIssueKey")
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao criar chamado Jira: {e}")
            return None

    @staticmethod
    def _speak_formatted_key(key: str) -> str:
        """Formata a chave do Jira para ser lida de forma natural via TTS.
        Exemplo: 'PROJ-1234' → 'P R O J, 1234'
        """
        parts = key.split("-")
        if len(parts) == 2:
            letters = " ".join(parts[0])
            return f"{letters}, {parts[1]}"
        return key
