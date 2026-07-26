"""
backfill_metadata.py — script one-off para popular os campos novos (grupos
A/B/C/D do XML Verint) nas chamadas já processadas antes desta feature.

Re-parseia o XML em disco (metadata-only, sem tocar STT/LLM) e reenvia só os
metadados via POST /internal/insights/{callRef}/metadata — nunca passa pelo
pipeline de IA, então não reprocessa transcrição/insights nem gasta API do
Gemini.

Uso: docker exec asteriskia-insights python -m src.backfill_metadata
"""

from __future__ import annotations

import asyncio
import logging

from src.backend_client import get_known_call_refs, submit_metadata
from src.discovery import discover_pairs
from src.config import AUDIO_DIR
from src.xml_parser import XmlParseError, parse_call_xml

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("asteriskia.insights.backfill_metadata")


def _build_metadata_payload(metadata) -> dict:
    return {
        "agentLoginId": metadata.agent_login_id,
        "customerNumber": metadata.customer_number,
        "organization": metadata.organization,
        "disconnectedBy": metadata.disconnected_by,
        "numberOfHolds": metadata.number_of_holds,
        "totalHoldTime": metadata.total_hold_time,
        "numberOfTransfers": metadata.number_of_transfers,
        "numberOfConferences": metadata.number_of_conferences,
        "wrapupTime": metadata.wrapup_time,
        "codec": metadata.codec,
        "missedRtpPackets": metadata.missed_rtp_packets,
        "decodingErrors": metadata.decoding_errors,
        "switchCallId": metadata.switch_call_id,
        "trunk": metadata.trunk,
        "captureType": metadata.capture_type,
        "datasourceName": metadata.datasource_name,
        "transferEvents": [
            {
                "transferredAt": event.transferred_at.isoformat() if event.transferred_at else None,
                "disconnectedBy": event.disconnected_by,
                "targetSwitchCallId": event.target_switch_call_id,
            }
            for event in metadata.transfer_events
        ],
    }


async def run() -> None:
    known_refs = await get_known_call_refs()
    # Só chamadas já concluídas (status='done') fazem sentido pro backfill —
    # pending/processing/error seguem o fluxo normal do watcher, que já vai
    # popular os campos novos na próxima vez que forem (re)processadas.
    done_refs = {ref for ref, status in known_refs.items() if status == "done"}

    pairs = discover_pairs(AUDIO_DIR)
    to_backfill = [p for p in pairs if p.call_ref in done_refs and p.xml_path]

    logger.info("%d chamada(s) concluída(s) encontrada(s) para backfill de metadados", len(to_backfill))

    ok, failed = 0, 0
    for pair in to_backfill:
        try:
            metadata = parse_call_xml(pair.xml_path)
            await submit_metadata(pair.call_ref, _build_metadata_payload(metadata))
            ok += 1
        except XmlParseError as e:
            logger.error("call_ref=%s: falha ao parsear XML no backfill — %s", pair.call_ref, e)
            failed += 1
        except Exception as e:
            logger.error("call_ref=%s: falha ao enviar metadados no backfill — %s", pair.call_ref, e)
            failed += 1

    logger.info("Backfill de metadados concluído: %d ok, %d falha(s)", ok, failed)


if __name__ == "__main__":
    asyncio.run(run())
