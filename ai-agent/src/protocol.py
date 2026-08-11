"""
protocol.py — Parser do Protocolo Audiosocket
Implementa a leitura e escrita de frames do protocolo Audiosocket
conforme especificação do Asterisk.

Formato do frame:
  [1 byte: tipo] [2 bytes: comprimento big-endian] [N bytes: payload]

Tipos de mensagem:
  0x00 = Hangup (encerramento)
  0x01 = UUID   (identificador da chamada, enviado pelo Asterisk na conexão)
  0x10 = Áudio  (payload PCM 8kHz 16bit signed little-endian mono)
  0xFF = Erro
"""

import logging
import struct
import uuid
import asyncio
from src.config import (
    AUDIOSOCKET_HEADER_SIZE,
    MSG_TYPE_UUID,
    MSG_TYPE_AUDIO,
    MSG_TYPE_HANGUP,
    MSG_TYPE_ERROR,
)

logger = logging.getLogger("asteriskia.protocol")

# Teto de sanidade para payload_length — frames reais nunca passam de 320 bytes
# (áudio) ou 16 bytes (UUID). Um valor absurdamente maior indica dessincronia do
# protocolo (lendo lixo como cabeçalho) — melhor descartar a conexão do que tentar
# ler um payload gigante que nunca vai bater com o frame real.
MAX_SANE_PAYLOAD_LENGTH = 8000


class AudiosocketFrame:
    """Representa um frame do protocolo Audiosocket."""

    def __init__(self, msg_type: int, payload: bytes):
        self.msg_type = msg_type
        self.payload = payload

    @property
    def is_audio(self) -> bool:
        return self.msg_type == MSG_TYPE_AUDIO

    @property
    def is_uuid(self) -> bool:
        return self.msg_type == MSG_TYPE_UUID

    @property
    def is_hangup(self) -> bool:
        return self.msg_type == MSG_TYPE_HANGUP

    @property
    def is_error(self) -> bool:
        return self.msg_type == MSG_TYPE_ERROR

    @property
    def call_uuid(self) -> str | None:
        """Retorna o UUID da chamada se for frame do tipo UUID."""
        if self.is_uuid and len(self.payload) == 16:
            return str(uuid.UUID(bytes=self.payload))
        return None


async def read_frame(reader: asyncio.StreamReader) -> AudiosocketFrame | None:
    """
    Lê um frame completo do stream Audiosocket.

    Args:
        reader: asyncio.StreamReader conectado ao Asterisk

    Returns:
        AudiosocketFrame ou None em caso de EOF
    """
    try:
        # Lê cabeçalho de 3 bytes
        header = await reader.readexactly(AUDIOSOCKET_HEADER_SIZE)
    except asyncio.IncompleteReadError:
        return None  # Conexão encerrada

    msg_type = header[0]
    payload_length = struct.unpack(">H", header[1:3])[0]  # Big-endian unsigned short

    if payload_length > MAX_SANE_PAYLOAD_LENGTH:
        logger.warning(
            "Frame com payload_length %d fora do teto de sanidade (%d) — descartando conexão",
            payload_length, MAX_SANE_PAYLOAD_LENGTH,
        )
        return None

    # Lê payload se houver comprimento > 0
    payload = b""
    if payload_length > 0:
        try:
            payload = await reader.readexactly(payload_length)
        except asyncio.IncompleteReadError:
            return None

    return AudiosocketFrame(msg_type, payload)


async def keep_alive_silence(writer: asyncio.StreamWriter, stop_event: asyncio.Event) -> None:
    """
    Envia frames de silêncio (PCM zero) continuamente até stop_event ser setado.

    Usado para manter o AudioSocket vivo enquanto operações lentas (TTS, STT)
    estão em andamento. Sem isso, o Asterisk encerra a chamada por timeout
    de inatividade (~1-2s sem receber áudio de volta).

    Frame: 320 bytes de zeros = 20ms de silêncio a 8kHz/16bit.

    Nota: drain() com timeout de 1s evita bloqueio quando o Asterisk
    demora a consumir o buffer — situação comum durante handshake DTLS/ICE.
    """
    SILENCE_FRAME = bytes([MSG_TYPE_AUDIO]) + struct.pack(">H", 320) + b"\x00" * 320
    INTERVAL = 0.02  # 20ms — mesma cadência do RTP

    while not stop_event.is_set():
        if writer.is_closing():
            break
        try:
            writer.write(SILENCE_FRAME)
            # drain com timeout: não bloqueia indefinidamente se o buffer travar
            await asyncio.wait_for(writer.drain(), timeout=1.0)
        except asyncio.TimeoutError:
            # Buffer cheio — Asterisk lento, mas conexão ainda viva; continua
            pass
        except (BrokenPipeError, ConnectionResetError, asyncio.CancelledError, OSError):
            break
        await asyncio.sleep(INTERVAL)


async def write_audio_paced(writer: asyncio.StreamWriter, pcm_data: bytes, record: list[bytes] | None = None) -> bool:
    """
    Envia áudio PCM com pacing real-time: 20ms de sleep por frame de 320 bytes.

    Diferente de write_audio (que envia tudo de uma vez), esta função sincroniza
    o envio com o ritmo de reprodução — a função retorna aproximadamente quando
    o último frame está sendo reproduzido no Asterisk. Isso permite calcular o
    tempo de espera pós-fala com precisão, sem margem de segurança excessiva.

    Se `record` for informado, acumula o PCM enviado (áudio da URA) na ordem
    cronológica em que foi tocado — usado para incluir as perguntas da URA
    na gravação final da chamada, junto com a voz do cliente.
    """
    FRAME_SIZE = 320
    DRAIN_EVERY = 5

    if writer.is_closing():
        return False

    if record is not None:
        record.append(pcm_data)

    frame_count = 0
    for i in range(0, len(pcm_data), FRAME_SIZE):
        if writer.is_closing():
            return False
        chunk = pcm_data[i:i + FRAME_SIZE]
        if len(chunk) < FRAME_SIZE:
            chunk = chunk + b'\x00' * (FRAME_SIZE - len(chunk))
        writer.write(bytes([MSG_TYPE_AUDIO]) + struct.pack(">H", FRAME_SIZE) + chunk)
        frame_count += 1
        await asyncio.sleep(0.02)  # cadência real-time: 20ms por frame de 320 bytes
        if frame_count % DRAIN_EVERY == 0:
            try:
                await writer.drain()
            except (BrokenPipeError, ConnectionResetError, asyncio.CancelledError):
                return False

    try:
        await writer.drain()
        return True
    except (BrokenPipeError, ConnectionResetError, asyncio.CancelledError):
        return False


async def write_audio(writer: asyncio.StreamWriter, pcm_data: bytes, record: list[bytes] | None = None) -> bool:
    """
    Envia um frame de áudio PCM de volta para o Asterisk via Audiosocket.

    O Asterisk espera áudio no formato: PCM 8kHz, 16bit, signed, little-endian, mono.
    Frames devem ter exatamente 320 bytes (20ms de áudio a 8kHz/16bit).

    Args:
        writer: asyncio.StreamWriter conectado ao Asterisk
        pcm_data: Bytes de áudio PCM a enviar
        record: se informado, acumula o PCM enviado (áudio da URA) para a
            gravação final da chamada — ver write_audio_paced()

    Returns:
        True se enviado com sucesso, False se a conexão foi encerrada pelo Asterisk.
    """
    FRAME_SIZE = 320  # 20ms a 8kHz/16bit

    if writer.is_closing():
        return False

    if record is not None:
        record.append(pcm_data)

    # Envia em frames de 320 bytes com drain a cada DRAIN_EVERY frames.
    # drain() a cada frame é muito lento; acumular tudo satura o buffer.
    # Draining a cada ~100ms (5 frames) é o equilíbrio correto.
    DRAIN_EVERY = 5  # drain a cada 5 frames = 100ms
    frame_count = 0

    for i in range(0, len(pcm_data), FRAME_SIZE):
        if writer.is_closing():
            return False

        chunk = pcm_data[i:i + FRAME_SIZE]
        if len(chunk) < FRAME_SIZE:
            chunk = chunk + b'\x00' * (FRAME_SIZE - len(chunk))

        length_bytes = struct.pack(">H", len(chunk))
        frame = bytes([MSG_TYPE_AUDIO]) + length_bytes + chunk
        writer.write(frame)
        frame_count += 1

        if frame_count % DRAIN_EVERY == 0:
            try:
                await writer.drain()
            except (BrokenPipeError, ConnectionResetError, asyncio.CancelledError):
                return False

    # Drain final para garantir que os últimos frames foram enviados
    try:
        await writer.drain()
        return True
    except (BrokenPipeError, ConnectionResetError, asyncio.CancelledError):
        return False


async def drain_reader(reader: asyncio.StreamReader, call_uuid: str = "") -> bool:
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
            frame = await asyncio.wait_for(read_frame(reader), timeout=0.005)
            if frame is None or frame.is_hangup:
                return True  # hangup detectado
            drained += 1
    except asyncio.TimeoutError:
        pass
    if drained:
        logger.debug("[%s] Drenados %d frames stale pós-TTS", call_uuid, drained)
    return False


async def wait_playback_and_drain(
    reader: asyncio.StreamReader,
    duration: float,
    elapsed: float,
    buffer_secs: float = 0.8,
    call_uuid: str = "",
) -> bool:
    """
    Aguarda o áudio residual pós-reprodução (se elapsed < duration) + buffer de
    segurança, depois drena frames stale acumulados no reader durante a fala —
    padrão comum entre os flows (URA e alertas) para não dessincronizar o STT
    seguinte nem confundir hangup com ruído acumulado.

    Retorna False se a chamada foi encerrada (hangup) durante a drenagem.
    """
    remaining = max(0.0, duration - elapsed) + buffer_secs
    await asyncio.sleep(remaining)
    hangup = await drain_reader(reader, call_uuid)
    return not hangup
