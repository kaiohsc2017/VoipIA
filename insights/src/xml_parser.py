"""
xml_parser.py — Parser do XML de metadados de chamada gerado pelo sistema de
gravação corporativo Verint (xmlns:x="http://www.verint.com/xmlns/recording20080320").

Módulo apartado do domínio Asterisk deste sistema — este schema não tem
nenhuma relação com o dialplan/CDR do AsteriskIA.

Extrai os campos mapeados para colunas próprias de call_audio_files; tudo o
mais fica disponível em `xml_raw` (dict completo via xmltodict) como
fallback, para nunca falhar por um campo ainda não mapeado.
"""

from __future__ import annotations

import logging
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime

import xmltodict

logger = logging.getLogger("asteriskia.insights.xml_parser")

_NS_URI = "http://www.verint.com/xmlns/recording20080320"
_NS = {"x": _NS_URI}


class XmlParseError(Exception):
    """Erro ao interpretar o XML de metadados — arquivo malformado ou schema inesperado."""


@dataclass(frozen=True)
class CallMetadata:
    call_ref: str
    call_starttime: datetime | None
    duration_seconds: int | None
    agent_name: str | None
    agent_id_verint: str | None
    extension: str | None
    ani: str | None
    dnis: str | None
    direction: str | None  # "inbound" | "outbound" | None
    skill: str | None
    xml_raw: dict


def _qname(local: str) -> str:
    return f"{{{_NS_URI}}}{local}"


def _find_tag_attribute(session_elem: ET.Element, key: str) -> str | None:
    """Busca o texto do primeiro x:attribute cujo atributo x:key == key, dentro
    de qualquer x:tags/x:tag do elemento de sessão (ex: 'skill', 'agentname')."""
    for attr in session_elem.findall(".//x:tags/x:tag/x:attribute", _NS):
        if attr.attrib.get(_qname("key")) == key:
            return (attr.text or "").strip() or None
    return None


def _parse_starttime(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw)
    except ValueError:
        logger.warning("starttime em formato inesperado, ignorado: %r", raw)
        return None


def _parse_direction(raw: str | None) -> str | None:
    if not raw:
        return None
    normalized = raw.strip().lower()
    if normalized in ("inbound", "outbound"):
        return normalized
    logger.warning("direction em valor inesperado, ignorado: %r", raw)
    return None


def _parse_int(raw: str | None) -> int | None:
    if not raw:
        return None
    try:
        return int(float(raw))
    except ValueError:
        return None


def parse_call_xml(xml_path: str) -> CallMetadata:
    """Lê e interpreta um XML Verint. Levanta XmlParseError se o arquivo não
    puder ser lido/parseado — o chamador decide o que fazer (ex: marcar
    call_audio_files.status = 'error')."""
    try:
        with open(xml_path, "rb") as f:
            raw_bytes = f.read()
        root = ET.fromstring(raw_bytes)
        xml_raw = xmltodict.parse(raw_bytes)
    except (ET.ParseError, OSError) as e:
        raise XmlParseError(f"falha ao ler/parsear {xml_path}: {e}") from e

    call_ref = root.attrib.get(_qname("ref"))
    if not call_ref:
        raise XmlParseError(f"XML sem atributo x:ref na raiz: {xml_path}")

    segment = root.find("x:segment", _NS)
    starttime = _parse_starttime(segment.findtext("x:starttime", namespaces=_NS)) if segment is not None else None
    duration_seconds = _parse_int(segment.findtext("x:duration", namespaces=_NS)) if segment is not None else None

    # MVP: primeira sessão encontrada (contact/sessions/session) — chamadas com
    # transferência/conferência podem ter mais de uma; tratar como trabalho futuro
    # se aparecer caso real (ver number_of_transfers/number_of_conferences no XML).
    session = root.find(".//x:contacts/x:contact/x:sessions/x:session", _NS)

    agent_name = session.findtext("x:employeename", namespaces=_NS) if session is not None else None
    agent_id_verint = session.findtext("x:agent_id", namespaces=_NS) if session is not None else None
    extension = session.findtext("x:extension", namespaces=_NS) if session is not None else None
    ani = session.findtext("x:ani", namespaces=_NS) if session is not None else None
    dnis = session.findtext("x:dnis", namespaces=_NS) if session is not None else None
    direction = _parse_direction(session.findtext("x:direction", namespaces=_NS)) if session is not None else None
    skill = _find_tag_attribute(session, "skill") if session is not None else None

    return CallMetadata(
        call_ref=call_ref,
        call_starttime=starttime,
        duration_seconds=duration_seconds,
        agent_name=(agent_name or "").strip() or None,
        agent_id_verint=(agent_id_verint or "").strip() or None,
        extension=(extension or "").strip() or None,
        ani=(ani or "").strip() or None,
        dnis=(dnis or "").strip() or None,
        direction=direction,
        skill=skill,
        xml_raw=xml_raw,
    )
