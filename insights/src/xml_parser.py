"""
xml_parser.py — Parser do XML de metadados de chamada gerado pelo sistema de
gravação corporativo Verint (xmlns:x="http://www.verint.com/xmlns/recording20080320").

Módulo apartado do domínio Asterisk deste sistema — este schema não tem
nenhuma relação com o dialplan/CDR do VoipIA.

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
class TransferEvent:
    """Um evento de transferência dentro de uma sessão — 0..N por chamada.

    target_switch_call_id é o `globalcallid` capturado no `Begin_Call` que
    precede este `Transferred` na ordem cronológica das tags — é a única
    forma de correlacionar com a gravação da perna de destino (ver
    TransferResolutionService no backend); pode ser None se não houver
    Begin_Call anterior no XML (a transferência ainda é registrada, só sem
    chave de correlação)."""

    transferred_at: datetime | None
    disconnected_by: str | None
    target_switch_call_id: str | None


@dataclass(frozen=True)
class CallMetadata:
    call_ref: str
    call_starttime: datetime | None
    duration_seconds: int | None
    agent_name: str | None
    agent_id_verint: str | None
    agent_login_id: str | None
    extension: str | None
    ani: str | None
    dnis: str | None
    direction: str | None  # "inbound" | "outbound" | None
    skill: str | None
    xml_raw: dict
    # ─── Grupo A — Identificação ───
    customer_number: str | None
    organization: str | None
    # ─── Grupo B — Qualidade ───
    disconnected_by: str | None  # "atendente" | "cliente" | None
    number_of_holds: int | None
    total_hold_time: int | None
    number_of_transfers: int | None
    number_of_conferences: int | None
    wrapup_time: int | None
    # ─── Grupo C — Técnico/Auditoria ───
    codec: str | None
    missed_rtp_packets: int | None
    decoding_errors: int | None
    switch_call_id: str | None
    trunk: str | None
    capture_type: str | None
    datasource_name: str | None
    # ─── Grupo D — Transferências ───
    transfer_events: list[TransferEvent]


def _qname(local: str) -> str:
    return f"{{{_NS_URI}}}{local}"


def _find_tag_attribute(elem: ET.Element, key: str) -> str | None:
    """Busca o texto do primeiro x:attribute cujo atributo x:key == key, dentro
    de qualquer x:tags/x:tag do elemento informado (session OU segment — ambos
    têm a mesma estrutura x:tags/x:tag/x:attribute)."""
    for attr in elem.findall(".//x:tags/x:tag/x:attribute", _NS):
        if attr.attrib.get(_qname("key")) == key:
            return (attr.text or "").strip() or None
    return None


def _find_last_tag_attribute(elem: ET.Element, key: str) -> str | None:
    """Como _find_tag_attribute, mas retorna a ÚLTIMA ocorrência em vez da
    primeira — necessário para campos que podem mudar de valor ao longo da
    sessão (ex: disconnectingparty se repete a cada transferência; o valor que
    importa pra 'quem desligou' da chamada como um todo é o mais recente)."""
    last: str | None = None
    for attr in elem.findall(".//x:tags/x:tag/x:attribute", _NS):
        if attr.attrib.get(_qname("key")) == key:
            last = (attr.text or "").strip() or None
    return last


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


def _parse_disconnected_by(raw: str | None) -> str | None:
    """EMPLOYEE -> atendente, OTHER -> cliente. Qualquer outro valor (ou
    ausente) fica None — nunca inventar um significado pra um valor que o
    Verint não documenta."""
    if not raw:
        return None
    normalized = raw.strip().upper()
    if normalized == "EMPLOYEE":
        return "atendente"
    if normalized == "OTHER":
        return "cliente"
    logger.warning("disconnectingparty em valor inesperado, ignorado: %r", raw)
    return None


def _resolve_customer_number(segment: ET.Element | None, session: ET.Element | None, direction: str | None) -> str | None:
    """Número do cliente, independente da direção da chamada:
    - outbound: session/tags calledparty (com fallback numberdialed) — o
      número discado é o cliente; ani/dnis da sessão nesse caso são o ramal
      do próprio atendente, não servem pra isso.
    - inbound (ou direção desconhecida): segment/tags signallingcallingparty —
      captura o número do chamador direto da sinalização SIP, antes de
      qualquer roteamento interno (ura/skill)."""
    if direction == "outbound":
        if session is None:
            return None
        called = _find_tag_attribute(session, "calledparty")
        dialed = _find_tag_attribute(session, "numberdialed")
        return called or dialed
    if segment is None:
        return None
    return _find_tag_attribute(segment, "signallingcallingparty")


def _extract_transfer_events(session: ET.Element | None) -> list[TransferEvent]:
    """Percorre as tags da sessão NA ORDEM em que aparecem no XML — cada
    Begin_Call atualiza o 'último globalcallid visto'; cada Transferred emite
    um TransferEvent usando esse globalcallid como chave de correlação com a
    gravação de destino (ver investigação no plano — nem sempre resolve, e
    isso é esperado)."""
    if session is None:
        return []
    tags_elem = session.find("x:tags", _NS)
    if tags_elem is None:
        return []

    events: list[TransferEvent] = []
    last_global_call_id: str | None = None

    for tag in tags_elem.findall("x:tag", _NS):
        attrs = {
            attr.attrib.get(_qname("key")): (attr.text or "").strip()
            for attr in tag.findall("x:attribute", _NS)
        }
        event_type = attrs.get("eventtype")
        if event_type == "Begin_Call":
            gcid = attrs.get("globalcallid")
            if gcid:
                last_global_call_id = gcid
        elif event_type == "Transferred":
            timestamp_raw = tag.attrib.get(_qname("timestamp"))
            events.append(TransferEvent(
                transferred_at=_parse_starttime(timestamp_raw),
                disconnected_by=_parse_disconnected_by(attrs.get("disconnectingparty")),
                target_switch_call_id=last_global_call_id,
            ))

    return events


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
    stream = segment.find(".//x:streams/x:stream", _NS) if segment is not None else None
    codec = stream.findtext("x:rtptypename", namespaces=_NS) if stream is not None else None
    missed_rtp_packets = _parse_int(stream.findtext("x:missedrtppackets", namespaces=_NS)) if stream is not None else None
    decoding_errors = _parse_int(stream.findtext("x:decodingerrors", namespaces=_NS)) if stream is not None else None
    capture_type = segment.findtext("x:capturetype", namespaces=_NS) if segment is not None else None

    contact = root.find(".//x:contacts/x:contact", _NS)
    number_of_transfers = _parse_int(contact.findtext("x:number_of_transfers", namespaces=_NS)) if contact is not None else None
    number_of_conferences = _parse_int(contact.findtext("x:number_of_conferences", namespaces=_NS)) if contact is not None else None

    # MVP: primeira sessão encontrada (contact/sessions/session) — chamadas com
    # transferência/conferência podem ter mais de uma; tratar como trabalho futuro
    # se aparecer caso real (ver number_of_transfers/number_of_conferences no XML).
    session = root.find(".//x:contacts/x:contact/x:sessions/x:session", _NS)

    agent_name = session.findtext("x:employeename", namespaces=_NS) if session is not None else None
    agent_id_verint = session.findtext("x:agent_id", namespaces=_NS) if session is not None else None
    # Login do agente no PBX/Avaya (tag "agentid", igual ao elemento session/pbx_login_id) —
    # diferente de agent_id_verint (chave interna da Verint) e de extension (ramal).
    agent_login_id = _find_tag_attribute(session, "agentid") if session is not None else None
    extension = session.findtext("x:extension", namespaces=_NS) if session is not None else None
    ani = session.findtext("x:ani", namespaces=_NS) if session is not None else None
    dnis = session.findtext("x:dnis", namespaces=_NS) if session is not None else None
    direction = _parse_direction(session.findtext("x:direction", namespaces=_NS)) if session is not None else None
    skill = _find_tag_attribute(session, "skill") if session is not None else None
    organization = _find_tag_attribute(session, "organization") if session is not None else None
    trunk = _find_tag_attribute(session, "trunk") if session is not None else None
    datasource_name = _find_tag_attribute(session, "datasourcename") if session is not None else None
    disconnected_by = _parse_disconnected_by(
        _find_last_tag_attribute(session, "disconnectingparty") if session is not None else None
    )
    number_of_holds = _parse_int(session.findtext("x:number_of_holds", namespaces=_NS)) if session is not None else None
    total_hold_time = _parse_int(session.findtext("x:total_hold_time", namespaces=_NS)) if session is not None else None
    wrapup_time = _parse_int(session.findtext("x:wrapup_time", namespaces=_NS)) if session is not None else None
    switch_call_id = session.findtext("x:switch_call_id", namespaces=_NS) if session is not None else None
    customer_number = _resolve_customer_number(segment, session, direction)
    transfer_events = _extract_transfer_events(session)

    return CallMetadata(
        call_ref=call_ref,
        call_starttime=starttime,
        duration_seconds=duration_seconds,
        agent_name=(agent_name or "").strip() or None,
        agent_id_verint=(agent_id_verint or "").strip() or None,
        agent_login_id=(agent_login_id or "").strip() or None,
        extension=(extension or "").strip() or None,
        ani=(ani or "").strip() or None,
        dnis=(dnis or "").strip() or None,
        direction=direction,
        skill=skill,
        xml_raw=xml_raw,
        customer_number=customer_number,
        organization=organization,
        disconnected_by=disconnected_by,
        number_of_holds=number_of_holds,
        total_hold_time=total_hold_time,
        number_of_transfers=number_of_transfers,
        number_of_conferences=number_of_conferences,
        wrapup_time=wrapup_time,
        codec=codec,
        missed_rtp_packets=missed_rtp_packets,
        decoding_errors=decoding_errors,
        switch_call_id=(switch_call_id or "").strip() or None,
        trunk=trunk,
        capture_type=capture_type,
        datasource_name=datasource_name,
        transfer_events=transfer_events,
    )
