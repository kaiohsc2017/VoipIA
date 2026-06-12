"""
jira_call_flow.py — Fluxo URA para abertura de chamado no Jira (Módulo 1)

Conduz a conversa com o cliente via TTS/STT usando Google Gemini,
coleta respostas para cada pergunta da URA e notifica o backend
para abrir o chamado no Jira ao final.

Com Function Calling habilitado, o Gemini pode:
  - Consultar status de pedido por CPF/protocolo
  - Abrir protocolos de suporte autonomamente
  - Responder perguntas contextuais durante a conversa
"""

import asyncio
import logging
from src.protocol import read_frame, write_audio
from src.services.gemini_service import GeminiService
from src.services import backend_client as bc

logger = logging.getLogger("asteriskia.flow.jira")


class JiraCallFlow:
    """
    Orquestrador do fluxo de URA para abertura de chamado Jira.

    Responsabilidades:
      1. Buscar perguntas ativas da URA no backend
      2. Para cada pergunta: TTS → captura áudio → STT → mapear resposta
      3. Acionar o backend para criar o chamado no Jira
      4. Confirmar o número do chamado ao cliente via TTS
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

        # 1. Boas-vindas
        await self._speak(
            "Bem-vindo ao sistema de atendimento AsteriskIA. "
            "Posso ajudar com consultas de pedidos, abertura de chamados ou suporte. "
            "Como posso te ajudar hoje?"
        )

        # 2. Turno conversacional livre com Function Calling
        #    O cliente pode perguntar sobre pedido, abrir chamado, etc.
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

        # 3. Busca perguntas estruturadas da URA
        questions = await self._fetch_questions()
        if not questions:
            await self._speak("Desculpe, ocorreu um erro ao carregar as perguntas. Tente novamente.")
            return

        # 4. Para cada pergunta: fala → ouve → transcreve → armazena
        for question in questions:
            answer = await self._ask_question(question["question_text"])
            if answer:
                self.collected_answers[question["jira_field_key"]] = answer
                logger.info(f"[{self.call_uuid}] Campo '{question['jira_field_key']}' = '{answer}'")

        # 5. Confirmação antes de abrir
        await self._speak(
            "Obrigado! Estou registrando seu chamado. Por favor, aguarde um momento."
        )

        # 6. Notifica backend para criar o chamado
        issue_key = await self._create_jira_issue()

        # 7. Confirma para o cliente
        if issue_key:
            await self._speak(
                f"Seu chamado foi aberto com sucesso. O número do seu protocolo é "
                f"{self._speak_formatted_key(issue_key)}. "
                f"Em breve nossa equipe entrará em contato. Obrigado!"
            )
        else:
            await self._speak(
                "Seu atendimento foi registrado. Nossa equipe entrará em contato. Obrigado!"
            )

        logger.info(f"[{self.call_uuid}] Fluxo URA Jira concluído | Chamado: {issue_key}")

    async def _ask_question(self, question_text: str) -> str | None:
        """
        Fala a pergunta via TTS e captura/transcreve a resposta do cliente.

        Args:
            question_text: Texto da pergunta a ser sintetizado em voz

        Returns:
            Texto transcrito da resposta do cliente ou None
        """
        # TTS: converte texto em áudio e envia para o Asterisk
        await self._speak(question_text)

        # Captura o áudio de resposta do cliente (max 10 segundos de silêncio)
        audio_buffer = await self._capture_audio(silence_timeout=3.0, max_duration=15.0)
        if not audio_buffer:
            return None

        # STT: transcreve áudio capturado
        transcription = await self.gemini.transcribe(audio_buffer)
        logger.debug(f"[{self.call_uuid}] STT resultado: '{transcription}'")
        return transcription

    async def _speak(self, text: str) -> None:
        """Converte texto em áudio (TTS) e envia ao Asterisk."""
        try:
            audio_pcm = await self.gemini.synthesize_speech(text)
            await write_audio(self.writer, audio_pcm)
            # Aguarda reprodução estimada (evita sobreposição)
            words = len(text.split())
            estimated_secs = max(1.0, words / 3.0)
            await asyncio.sleep(estimated_secs)
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro TTS: {e}")

    async def _capture_audio(
        self,
        silence_timeout: float = 3.0,
        max_duration: float = 15.0
    ) -> bytes:
        """
        Captura áudio do cliente até detectar silêncio ou atingir o tempo máximo.

        Args:
            silence_timeout: Segundos de silêncio para encerrar captura
            max_duration: Duração máxima de captura em segundos

        Returns:
            Bytes de áudio PCM concatenados
        """
        audio_chunks: list[bytes] = []
        SILENCE_THRESHOLD = 300     # RMS mínimo para considerar voz ativa
        SAMPLE_RATE = 8000          # Hz
        FRAME_BYTES = 320           # 20ms de áudio a 8kHz/16bit

        silence_frames = 0
        max_frames = int(max_duration * (SAMPLE_RATE / (FRAME_BYTES // 2)))
        silence_limit = int(silence_timeout * (SAMPLE_RATE / (FRAME_BYTES // 2)))

        for _ in range(max_frames):
            frame = await asyncio.wait_for(read_frame(self.reader), timeout=5.0)
            if frame is None or frame.is_hangup:
                break
            if not frame.is_audio:
                continue

            audio_chunks.append(frame.payload)

            # Detecção de silêncio por energia RMS simplificada
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

    async def _fetch_questions(self) -> list[dict]:
        """Busca perguntas ativas da URA no backend (autenticado)."""
        try:
            return await bc.get("/api/v1/ura/questions")
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao buscar perguntas URA: {e}")
            return []

    async def _create_jira_issue(self) -> str | None:
        """Envia dados coletados ao backend para criação do chamado no Jira (autenticado)."""
        try:
            payload = {"callUuid": self.call_uuid, "fields": self.collected_answers}
            data = await bc.post("/api/v1/calls/register", json=payload)
            return data.get("jiraIssueKey")
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao criar chamado Jira: {e}")
            return None

    @staticmethod
    def _speak_formatted_key(key: str) -> str:
        """
        Formata a chave do Jira para ser lida de forma natural via TTS.
        Exemplo: 'PROJ-1234' → 'P R O J, 1234'
        """
        parts = key.split("-")
        if len(parts) == 2:
            letters = " ".join(parts[0])
            return f"{letters}, {parts[1]}"
        return key
