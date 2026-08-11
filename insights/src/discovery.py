"""
discovery.py — Descoberta de pares .wav/.xml de gravação em /opt/audio.

/opt/audio é um diretório COMPARTILHADO com arquivos de outros serviços
(backups .tar, imagens, planilhas etc.) — nunca varrer assumindo que só há
pares de chamada. O nome de cada arquivo Verint segue o padrão
"{call_ref}---{uuid}.{wav|xml}", onde call_ref é o mesmo valor do atributo
x:ref do XML — mas o uuid de sufixo DIFERE entre o .wav e o .xml do mesmo
registro, então o agrupamento é feito pelo prefixo numérico, nunca pelo nome
completo do arquivo.
"""

from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass

logger = logging.getLogger("asteriskia.insights.discovery")

# Ex: "256001003459910---74af155b-1da6-4644-9f67-e01a7e7d301d.wav"
_FILENAME_RE = re.compile(
    r"^(?P<call_ref>\d+)---"
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    r"\.(?P<ext>wav|xml)$"
)


@dataclass(frozen=True)
class AudioPair:
    call_ref: str
    wav_path: str
    xml_path: str


def discover_pairs(audio_dir: str) -> list[AudioPair]:
    """Varre audio_dir (não recursivo) e retorna os pares .wav+.xml completos,
    agrupados pelo prefixo numérico (call_ref). Arquivos sem par completo ou
    que não batem o padrão de nome são ignorados silenciosamente — o
    diretório é compartilhado e contém arquivos não relacionados."""
    wav_by_ref: dict[str, str] = {}
    xml_by_ref: dict[str, str] = {}

    try:
        entries = os.listdir(audio_dir)
    except OSError as e:
        logger.error("Não foi possível listar %s: %s", audio_dir, e)
        return []

    for name in entries:
        match = _FILENAME_RE.match(name)
        if not match:
            continue
        call_ref = match.group("call_ref")
        full_path = os.path.join(audio_dir, name)
        if match.group("ext") == "wav":
            wav_by_ref[call_ref] = full_path
        else:
            xml_by_ref[call_ref] = full_path

    pairs = [
        AudioPair(call_ref=ref, wav_path=wav_by_ref[ref], xml_path=xml_by_ref[ref])
        for ref in wav_by_ref.keys() & xml_by_ref.keys()
    ]

    incomplete = (wav_by_ref.keys() ^ xml_by_ref.keys())
    if incomplete:
        logger.debug(
            "%d call_ref com apenas um dos dois arquivos (wav ou xml) — aguardando o par completo: %s",
            len(incomplete), sorted(incomplete),
        )

    return pairs
