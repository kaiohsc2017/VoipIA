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
import struct
import time
import wave
import webrtcvad
from src.protocol import read_frame, write_audio_paced
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

# Threshold RMS — usado só como fallback do VAD para frames de tamanho não-padrão.
_SILENCE_THRESHOLD = 700

# Agressividade do WebRTC VAD (0-3). Nível 3 favorece rejeitar ruído/som
# ambiente ao redor, focando na voz de quem fala diretamente ao microfone —
# troca-off aceitável de eventualmente cortar sílabas bem baixinhas.
_VAD_AGGRESSIVENESS = 3

# Segundos de silêncio contínuo para encerrar a captura (perguntas estruturadas)
_SILENCE_TIMEOUT_SECS = 2.0

# Timeout de silêncio para o turno livre (mais curto — o usuário pode não saber que deve falar)
_TURNO_LIVRE_SILENCE_SECS = 3.0

# Duração máxima de captura em segundos (relógio real — garante encerramento independente de frame timing)
_MAX_CAPTURE_TURNO_LIVRE = 6.0   # turno livre: até 6s de relógio
_MAX_CAPTURE_PERGUNTA    = 12.0  # perguntas: até 12s de relógio

# Instância única do VAD — sem estado por chamada, seguro reaproveitar entre chamadas concorrentes.
_vad = webrtcvad.Vad(_VAD_AGGRESSIVENESS)


def _is_speech_frame(payload: bytes) -> bool:
    """
    Classifica um frame de 20ms como voz humana (True) ou ruído/silêncio (False).

    Usa o WebRTC VAD em vez de um threshold de energia (RMS) fixo — o VAD
    analisa características espectrais da fala e é muito mais robusto a
    ruído ambiente, focando na voz de quem fala diretamente ao microfone em
    vez de ser enganado por som ao redor.
    """
    if len(payload) != 320:  # fora do frame padrão 20ms/8kHz — fallback por energia
        samples = struct.unpack(f"<{len(payload)//2}h", payload)
        rms = (sum(x * x for x in samples) / len(samples)) ** 0.5
        return rms >= _SILENCE_THRESHOLD
    return _vad.is_speech(payload, _SAMPLE_RATE)


def _resolve_vad_aggressiveness(raw: str | None) -> int:
    """Converte o valor configurado na tela de Fluxo URA (0-3) para o modo do VAD, com fallback seguro."""
    try:
        level = int(str(raw).strip())
    except (TypeError, ValueError):
        return _VAD_AGGRESSIVENESS
    return level if level in (0, 1, 2, 3) else _VAD_AGGRESSIVENESS


def _trim_silence(pcm: bytes, frame_size: int = 320) -> bytes:
    """
    Remove frames de silêncio/ruído do início e fim do PCM.
    Reduz o tamanho do áudio enviado ao STT — menos dados = resposta mais rápida.
    """
    frames = [pcm[i:i+frame_size] for i in range(0, len(pcm), frame_size) if len(pcm[i:i+frame_size]) == frame_size]
    if not frames:
        return pcm

    # Encontra primeiro frame com voz — mantém 5 frames antes (100ms) para
    # não cortar consoantes iniciais (ex: "k" de "kaio", "c" de "cinco")
    start = 0
    for i, f in enumerate(frames):
        if _is_speech_frame(f):
            start = max(0, i - 5)
            break

    # Encontra último frame com voz
    end = len(frames)
    for i in range(len(frames) - 1, -1, -1):
        if _is_speech_frame(frames[i]):
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
        self.call_uuid          = call_uuid
        self.reader             = reader
        self.writer             = writer
        self.caller_number      = caller_number
        self.ai                 = AIService()
        self.collected_answers: dict[str, str] = {}
        self._transcriptions: list[str]        = []
        self._recorded_audio: list[bytes]      = []   # PCM da chamada em ordem cronológica (URA + cliente) para gravação WAV
        self._call_start_time: float           = 0.0  # início do fluxo para cálculo de duração

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
        _vad.set_mode(_resolve_vad_aggressiveness(settings.get("vad_aggressiveness")))

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
                self.collected_answers[key] = answer
                self._transcriptions.append(f"[{question['question_text']}]: {answer}")
                logger.info("[%s] %s = %r", self.call_uuid, key, answer)

        # 5. Confirmação — mensagem estática, serve do cache
        ok = await self._play_cached("Obrigado! Estou registrando seu chamado. Aguarde um momento.")
        if not ok:
            return

        # 6. Grava WAV com voz do chamador (MixMonitor não captura audio do AudioSocket)
        audio_path = f"/var/spool/asterisk/monitor/{self.call_uuid}.wav"
        try:
            self._write_wav(audio_path)
        except Exception as e:
            logger.warning("[%s] Erro ao salvar gravação: %s", self.call_uuid, e)
            audio_path = None

        # 7. Cria chamado no Jira via backend
        call_duration = int(time.monotonic() - self._call_start_time)
        issue_key = await self._create_jira_issue(audio_path=audio_path, duration_secs=call_duration)

        # 7. Encerramento
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

        Com write_audio_paced, a função retorna ~junto com o fim da reprodução.
        Ao final, drena frames acumulados no reader durante o TTS.
        """
        import time
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

    async def _play_cached(self, text: str) -> bool:
        """
        Reproduz mensagem via PCM pré-gerado em disco — sem chamada TTS em tempo real.

        Zero latência de TTS: o PCM é lido do cache local.
        Fallback automático para _speak() se o cache não estiver disponível.
        """
        import time
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

            remaining = max(0.0, duration - elapsed) + _POST_SPEAK_BUFFER_SECS
            await asyncio.sleep(remaining)

            hangup = await self._drain_reader()
            return not hangup
        except (BrokenPipeError, ConnectionResetError):
            logger.warning("[%s] Pipe quebrado durante reprodução do cache", self.call_uuid)
            return False
        except Exception as e:
            logger.error("[%s] Erro na reprodução do cache: %s — usando TTS em tempo real", self.call_uuid, e)
            return await self._speak(text)

    async def _drain_reader(self) -> bool:
        """
        Descarta frames acumulados no reader durante geração/reprodução do TTS.

        O Asterisk envia áudio do microfone continuamente — mesmo quando o
        cliente está escutando a URA. Esses frames precisam ser descartados
        antes de iniciar a captura real para que o STT não transcreva ruído
        de fundo capturado durante a fala da URA como resposta do cliente.

        Timeout 5ms: esvazia o buffer já acumulado (leituras de buffer são
        instantâneas), e é MENOR que o intervalo entre frames do Asterisk
        (~20ms). Após o buffer esvaziar, o próximo frame leva ~20ms para
        chegar — como 20ms > 5ms, o timeout é acionado e o loop sai.

        Usar 50ms causava loop infinito: Asterisk envia 1 frame a cada 20ms,
        e 20ms < 50ms → novo frame sempre chegava antes do timeout → trava.

        Retorna True se detectou hangup durante a drenagem.
        """
        drained = 0
        try:
            while True:
                frame = await asyncio.wait_for(read_frame(self.reader), timeout=0.005)
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

    # Mapa de palavras numéricas → dígito (BR Portuguese)
    _NUMBER_WORDS: dict[str, str] = {
        "zero": "0", "um": "1", "uma": "1", "dois": "2", "duas": "2",
        "três": "3", "tres": "3", "quatro": "4", "cinco": "5",
        "seis": "6", "sete": "7", "oito": "8", "nove": "9",
    }

    @staticmethod
    def _build_stt_hint(question_text: str, field_key: str, expected_values: str = "") -> str:
        """
        Monta o prompt de contexto enviado ao STT conforme o tipo de campo.

        O contexto reduz ambiguidade: o modelo sabe que deve transcrever
        um ramal (dígitos), um login (com ponto), tipo de ticket ou texto livre.
        """
        fk = field_key.lower()

        if any(k in fk for k in ("telefone", "ramal", "phone", "fone")):
            return (
                f"Contexto: {question_text}\n"
                "O usuário irá falar um número de ramal ou telefone dígito por dígito "
                "(ex: 'cinco zero zero quatro'). "
                "Transcreva APENAS os dígitos em algarismos, sem espaços (ex: 5004). "
                "Ignore palavras como 'ramal' ou 'número'. "
                "Retorne somente os dígitos."
            )

        if any(k in fk for k in ("nome", "login", "user", "email", "mail")):
            return (
                f"Contexto: {question_text}\n"
                "O usuário irá falar um login de rede no formato nome.sobrenome. "
                "Se disser 'ponto', escreva '.' (sem espaço). "
                "Exemplo: 'kaio ponto correa' → 'kaio.correa'. "
                "Retorne apenas o login, sem pontuação extra."
            )

        if any(k in fk for k in ("priority", "prioridade", "urgencia", "urgência")):
            return (
                f"Contexto: {question_text}\n"
                "O usuário irá falar a prioridade: Baixa, Média ou Alta. "
                "Transcreva exatamente a palavra de prioridade que foi dita. "
                "Normalize variações: 'media' → 'Média', 'alta urgencia' → 'Alta'."
            )

        # Tipo de ticket (incidente vs. requisição) — campo type_ticket, issuetype ou similar
        if any(k in fk for k in ("type_ticket", "issuetype", "tipo")) or (
            "type" in fk and not any(k in fk for k in ("telefone", "ramal", "nome", "login", "priority", "prioridade"))
        ):
            opts = expected_values if expected_values else "Incidente, Requisição"
            return (
                f"Contexto: {question_text}\n"
                f"O usuário deve escolher entre: {opts}. "
                "Transcreva exatamente uma das opções. "
                "Mapeie: 'problema', 'falha', 'parou', 'erro' → 'Incidente'; "
                "'solicitação', 'nova', 'acesso', 'instalação', 'serviço' → 'Requisição'. "
                "Retorne apenas a opção escolhida."
            )

        # Campo com valores esperados explícitos (qualquer campo configurado com expected_values)
        if expected_values:
            vals = [v.strip() for v in expected_values.split(",") if v.strip()]
            if vals:
                return (
                    f"Contexto: {question_text}\n"
                    f"O usuário deve responder com uma destas opções: {', '.join(vals)}. "
                    "Transcreva exatamente uma das opções acima. "
                    "Retorne apenas a opção escolhida."
                )

        return (
            f"Contexto: {question_text}\n"
            "Transcreva em português do Brasil exatamente o que foi dito. "
            "Retorne apenas o texto transcrito."
        )

    @staticmethod
    def _normalize_transcription(text: str, field_key: str, expected_values: str = "") -> str:
        """
        Normaliza a transcrição do STT para o campo específico.

        Converte palavras faladas em representações canônicas:
        - Ramais/telefones: palavras numéricas → dígitos, remove prefixo "ramal"
        - Logins: "ponto" → ".", remove espaços entre partes do login
        - Prioridades: normaliza capitalização
        """
        import re
        fk = field_key.lower()

        if any(k in fk for k in ("telefone", "ramal", "phone", "fone")):
            # Converte palavras numéricas para dígitos
            for word, digit in JiraCallFlow._NUMBER_WORDS.items():
                text = re.sub(rf'\b{word}\b', digit, text, flags=re.IGNORECASE)
            # Remove espaços entre dígitos consecutivos: "5 0 0 4" → "5004"
            text = re.sub(r'(?<=\d)\s+(?=\d)', '', text)
            # Remove prefixo "ramal " ou "número " se capturado
            text = re.sub(r'^(ramal|número|numero|tel|fone)\s*', '', text, flags=re.IGNORECASE)
            return text.strip()

        if any(k in fk for k in ("nome", "login", "user", "email", "mail")):
            # Converte "ponto" → "." (com ou sem espaços ao redor)
            text = re.sub(r'\s*\bponto\b\s*', '.', text, flags=re.IGNORECASE)
            # Remove espaços ao redor de pontos restantes: "kaio . correa" → "kaio.correa"
            text = re.sub(r'\s*\.\s*', '.', text)
            # Remove prefixo "login" ou "usuário" se capturado acidentalmente
            text = re.sub(r'^(login|usuário|usuario|user)\s*[:;]?\s*', '', text, flags=re.IGNORECASE)
            return text.strip()

        if any(k in fk for k in ("priority", "prioridade", "urgencia", "urgência")):
            tl = text.lower().strip()
            if "alta" in tl or "urgente" in tl or "crítica" in tl or "critica" in tl:
                return "Alta"
            if "média" in tl or "media" in tl or "moderada" in tl:
                return "Média"
            if "baixa" in tl or "menor" in tl:
                return "Baixa"
            return text

        # Tipo de ticket — normaliza para Incidente ou Requisição
        if any(k in fk for k in ("type_ticket", "issuetype", "tipo")) or (
            "type" in fk and not any(k in fk for k in ("telefone", "ramal", "nome", "login", "priority", "prioridade"))
        ):
            tl = text.lower().strip()
            if any(w in tl for w in ("incidente", "incident", "problema", "bug", "erro", "falha", "parou", "quebrou", "crítico", "critico")):
                return "Incidente"
            if any(w in tl for w in ("solicitação", "solicitacao", "requisição", "requisicao", "request", "nova", "novo", "serviço", "servico", "acesso", "instalação", "instalacao", "abertura")):
                return "Requisição"
            return text

        return text

    async def _listen_and_transcribe(
        self,
        silence_timeout: float = _SILENCE_TIMEOUT_SECS,
        max_duration: float = _MAX_CAPTURE_PERGUNTA,
        hint: str = "",
        field_key: str = "",
        expected_values: str = "",
    ) -> str | None:
        """Captura áudio do cliente, remove silêncio e transcreve via STT."""
        audio = await self._capture_audio(silence_timeout=silence_timeout, max_duration=max_duration)
        if not audio:
            return None
        audio = _trim_silence(audio)
        if len(audio) < 320 * 10:  # menos de 10 frames (~0.2s) → sem voz real
            return None
        try:
            text = await self.ai.transcribe(audio, hint=hint)
            if not text:
                return None
            # Filtra transcrições de ruído/música — o STT captou o áudio da URA
            text_lower = text.lower().strip()
            if any(p in text_lower for p in self._NOISE_PATTERNS):
                logger.debug("[%s] STT filtrou ruído: %r", self.call_uuid, text)
                return None
            text = self._normalize_transcription(text, field_key, expected_values)
            logger.info("[%s] STT[%s]: %r", self.call_uuid, field_key or "?", text)
            return text
        except Exception as e:
            logger.error("[%s] Erro STT: %s", self.call_uuid, e)
            return None

    async def _ask_question(self, question_text: str, field_key: str = "", expected_values: str = "") -> str | None:
        """Reproduz a pergunta (do cache) e captura/transcreve a resposta do cliente."""
        ok = await self._play_cached(question_text)
        if not ok:
            return None
        hint = self._build_stt_hint(question_text, field_key, expected_values)
        return await self._listen_and_transcribe(hint=hint, field_key=field_key, expected_values=expected_values)

    async def _capture_audio(
        self,
        silence_timeout: float = _SILENCE_TIMEOUT_SECS,
        max_duration: float = _MAX_CAPTURE_PERGUNTA,
    ) -> bytes:
        """
        Captura frames de áudio até silêncio ou timeout de relógio real.

        Usa time.monotonic() para garantir encerramento em max_duration segundos
        independente de overhead do asyncio ou jitter de frames WebRTC/RTP.
        """
        import time

        audio_chunks: list[bytes] = []
        frame_duration = 320 / (_SAMPLE_RATE * _BYTES_SAMPLE)  # 0.02s por frame
        silence_limit  = int(silence_timeout / frame_duration)

        silence_count = 0
        t_start = time.monotonic()

        while True:
            # Limite de tempo de relógio real — imune a jitter de frames
            if time.monotonic() - t_start >= max_duration:
                logger.debug("[%s] Captura encerrada por limite de %.1fs", self.call_uuid, max_duration)
                break

            try:
                frame = await asyncio.wait_for(read_frame(self.reader), timeout=2.0)
            except asyncio.TimeoutError:
                logger.debug("[%s] Timeout de frame — encerrando captura", self.call_uuid)
                break

            if frame is None or frame.is_hangup:
                break
            if not frame.is_audio:
                continue

            payload = frame.payload
            audio_chunks.append(payload)
            self._recorded_audio.append(payload)  # acumula para gravação WAV

            if not _is_speech_frame(payload):
                silence_count += 1
                if silence_count >= silence_limit:
                    elapsed = time.monotonic() - t_start
                    logger.debug("[%s] Silêncio detectado em %.1fs", self.call_uuid, elapsed)
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

    def _write_wav(self, path: str) -> None:
        """
        Escreve em disco a chamada completa (perguntas da URA + respostas do
        cliente, na ordem em que ocorreram) como WAV 8kHz/16bit/mono.
        """
        if not self._recorded_audio:
            logger.warning("[%s] Sem áudio gravado — arquivo WAV não será criado", self.call_uuid)
            return
        pcm = b"".join(self._recorded_audio)
        with wave.open(path, 'wb') as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)       # 16-bit
            wf.setframerate(_SAMPLE_RATE)
            wf.writeframes(pcm)
        logger.info("[%s] Gravação salva: %s (%d bytes / %.1fs)",
                    self.call_uuid, path, len(pcm), len(pcm) / (_SAMPLE_RATE * _BYTES_SAMPLE))

    async def _create_jira_issue(
        self,
        audio_path: str | None = None,
        duration_secs: int = 0,
    ) -> str | None:
        try:
            full_transcription = "\n".join(self._transcriptions)
            self.collected_answers.setdefault("description", full_transcription)
            if self.caller_number and self.caller_number != "desconhecido":
                self.collected_answers.setdefault("customfield_telefone", self.caller_number)

            payload = {
                "callUuid":         self.call_uuid,
                "fields":           self.collected_answers,
                "audioFilePath":    audio_path or f"/var/spool/asterisk/monitor/{self.call_uuid}.wav",
                "transcription":    full_transcription,
                "callerNumber":     self.caller_number,
                "callDurationSecs": duration_secs,
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
