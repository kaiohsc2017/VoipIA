"""
services/audio_cache.py — Cache de áudio TTS pré-gerado para mensagens estáticas da URA.

Chave: SHA-256(texto)[:32] → arquivo {hash}.pcm em disco.
Texto alterado → hash diferente → cache miss → regeneração automática.
"""
import asyncio
import hashlib
import logging
import os
from pathlib import Path

logger = logging.getLogger("asteriskia.audio_cache")


class AudioCacheService:
    """
    Cache de PCM (8kHz/16bit/mono) para mensagens estáticas da URA.

    Evita chamadas TTS em tempo real para textos fixos → latência ~0ms vs 5-7s.
    Invalidação por conteúdo: texto diferente produz hash diferente, portanto
    novo arquivo — sem necessidade de sinalização explícita do backend.
    """

    def __init__(self, cache_dir: Path | None = None) -> None:
        self._dir = cache_dir or Path(os.getenv("URA_CACHE_DIR", "/cache/ura"))
        try:
            self._dir.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            logger.warning("Não foi possível criar diretório de cache %s: %s", self._dir, e)
        self._locks: dict[str, asyncio.Lock] = {}

    def _path(self, text: str) -> Path:
        key = hashlib.sha256(text.encode()).hexdigest()[:32]
        return self._dir / f"{key}.pcm"

    async def get_or_generate(self, text: str, ai_service) -> bytes:
        """
        Retorna PCM do disco ou gera via TTS não-streaming e persiste.

        Thread-safe: asyncio.Lock por hash impede geração duplicada simultânea.
        Retorna b"" se texto vazio ou se a geração falhar (caller usa fallback TTS).
        """
        if not text or not text.strip():
            return b""

        path = self._path(text)
        if path.exists():
            data = path.read_bytes()
            logger.debug("Cache hit: %s (%d bytes)", path.name, len(data))
            return data

        lock_key = path.stem
        if lock_key not in self._locks:
            self._locks[lock_key] = asyncio.Lock()

        async with self._locks[lock_key]:
            if path.exists():
                return path.read_bytes()

            logger.info("Cache miss — gerando TTS: %r", text[:80])
            try:
                pcm = await ai_service.synthesize_speech(text)
                if pcm:
                    path.write_bytes(pcm)
                    logger.info("Cache salvo: %s (%d bytes)", path.name, len(pcm))
                    return pcm
                logger.warning("TTS retornou vazio para: %r", text[:80])
                return b""
            except Exception as e:
                logger.error("Erro ao gerar cache TTS: %s", e)
                return b""

    async def warm_up(self, texts: list[str], ai_service) -> None:
        """Pré-gera PCM para textos ainda não cacheados em disco."""
        pending = [t for t in texts if t and t.strip() and not self._path(t).exists()]
        if not pending:
            logger.info("Cache warm-up: todos os %d textos já presentes", len(texts))
            return
        logger.info("Cache warm-up: gerando %d/%d textos pendentes", len(pending), len(texts))
        for text in pending:
            await self.get_or_generate(text, ai_service)
        logger.info("Cache warm-up concluído (%d novos arquivos)", len(pending))


# Singleton de módulo — compartilhado entre chamadas simultâneas
audio_cache = AudioCacheService()
