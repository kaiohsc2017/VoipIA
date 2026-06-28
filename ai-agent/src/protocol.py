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

import struct
import asyncio
from src.config import (
    AUDIOSOCKET_HEADER_SIZE,
    MSG_TYPE_UUID,
    MSG_TYPE_AUDIO,
    MSG_TYPE_HANGUP,
    MSG_TYPE_ERROR,
)


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
            # Converte 16 bytes raw para string UUID formatada
            raw = self.payload.hex()
            return f"{raw[0:8]}-{raw[8:12]}-{raw[12:16]}-{raw[16:20]}-{raw[20:32]}"
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


async def write_audio_paced(writer: asyncio.StreamWriter, pcm_data: bytes) -> bool:
    """
    Envia áudio PCM com pacing real-time: 20ms de sleep por frame de 320 bytes.

    Diferente de write_audio (que envia tudo de uma vez), esta função sincroniza
    o envio com o ritmo de reprodução — a função retorna aproximadamente quando
    o último frame está sendo reproduzido no Asterisk. Isso permite calcular o
    tempo de espera pós-fala com precisão, sem margem de segurança excessiva.
    """
    FRAME_SIZE = 320
    DRAIN_EVERY = 5

    if writer.is_closing():
        return False

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


async def write_audio(writer: asyncio.StreamWriter, pcm_data: bytes) -> bool:
    """
    Envia um frame de áudio PCM de volta para o Asterisk via Audiosocket.

    O Asterisk espera áudio no formato: PCM 8kHz, 16bit, signed, little-endian, mono.
    Frames devem ter exatamente 320 bytes (20ms de áudio a 8kHz/16bit).

    Args:
        writer: asyncio.StreamWriter conectado ao Asterisk
        pcm_data: Bytes de áudio PCM a enviar

    Returns:
        True se enviado com sucesso, False se a conexão foi encerrada pelo Asterisk.
    """
    FRAME_SIZE = 320  # 20ms a 8kHz/16bit

    if writer.is_closing():
        return False

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
