"""
test_xml_parser.py — cobre a extração dos campos novos (grupos A/B/C) e dos
eventos de transferência (grupo D) a partir de XMLs reais do Verint.

Fixtures em tests/fixtures/:
- outbound_sem_transferencia.xml: chamada efetuada (Outbound), 0 transferências.
- inbound_com_transferencia.xml: chamada recebida (Inbound), 1 transferência real.
- inbound_com_transferencia_2.xml: outra chamada recebida com 1 transferência real
  (usada só pra variar valores de codec/RTP).
- inbound_com_2_transferencias_sintetico.xml: XML sintético com 2 transferências
  em sequência — não existe caso real assim no lote de 52 chamadas ainda.
"""

from __future__ import annotations

import os

import pytest

from src.xml_parser import parse_call_xml, TransferEvent

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES_DIR, name)


class TestGrupoAIdentificacao:
    def test_outbound_customer_number_usa_calledparty(self):
        metadata = parse_call_xml(fixture_path("outbound_sem_transferencia.xml"))
        assert metadata.direction == "outbound"
        # session/tags calledparty="4646335584991129754" tem precedência sobre numberdialed
        assert metadata.customer_number == "4646335584991129754"

    def test_inbound_customer_number_usa_signallingcallingparty(self):
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert metadata.direction == "inbound"
        assert metadata.customer_number == "16991379262"

    def test_organization_extraido_da_sessao(self):
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert metadata.organization == "Agentes-CM03"

    def test_agent_login_id_diferente_de_agent_id_verint(self):
        """agentid (login PBX) e agent_id/ultraagentid (chave interna Verint) são
        campos distintos no mesmo XML — não podem ser confundidos."""
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert metadata.agent_login_id == "39773"
        assert metadata.agent_id_verint == "256003639"
        assert metadata.agent_login_id != metadata.agent_id_verint

    def test_ani_dnis_brutos_preservados_sem_troca(self):
        """O parser NUNCA troca ani/dnis — a regra de exibição por direção
        (decisão 9) vive só no backend Java, não aqui."""
        metadata = parse_call_xml(fixture_path("outbound_sem_transferencia.xml"))
        assert metadata.ani == "13508"
        assert metadata.dnis == "840284991129754"


class TestGrupoBQualidade:
    def test_disconnected_by_employee_vira_atendente(self):
        metadata = parse_call_xml(fixture_path("outbound_sem_transferencia.xml"))
        assert metadata.disconnected_by == "atendente"

    def test_holds_e_wrapup(self):
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert metadata.number_of_holds == 1
        assert metadata.total_hold_time == 1
        assert metadata.wrapup_time == 0

    def test_number_of_transfers_e_conferences_do_contact(self):
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert metadata.number_of_transfers == 1
        assert metadata.number_of_conferences == 0

    def test_chamada_sem_transferencia_tem_contador_zero(self):
        metadata = parse_call_xml(fixture_path("outbound_sem_transferencia.xml"))
        assert metadata.number_of_transfers == 0


class TestGrupoCTecnico:
    def test_codec_e_metricas_rtp(self):
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert metadata.codec == "G729A"
        assert metadata.missed_rtp_packets == 0
        assert metadata.decoding_errors == 0

    def test_switch_call_id_trunk_datasource(self):
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert metadata.switch_call_id == "00001140071781525935"
        assert metadata.trunk == "3F63"
        assert metadata.datasource_name == "CM03"
        assert metadata.capture_type == "IP"


class TestGrupoDTransferencias:
    def test_zero_transferencias_nao_emite_eventos(self):
        metadata = parse_call_xml(fixture_path("outbound_sem_transferencia.xml"))
        assert metadata.transfer_events == []

    def test_uma_transferencia_real_emite_um_evento_com_correlacao(self):
        metadata = parse_call_xml(fixture_path("inbound_com_transferencia.xml"))
        assert len(metadata.transfer_events) == 1
        event = metadata.transfer_events[0]
        assert isinstance(event, TransferEvent)
        assert event.disconnected_by == "atendente"
        assert event.target_switch_call_id == "00001140131781525981"
        assert event.transferred_at is not None

    def test_duas_transferencias_em_sequencia_mantem_globalcallid_correto_por_evento(self):
        """Cada Transferred deve pegar o globalcallid do Begin_Call
        imediatamente anterior a ELE, não sempre o primeiro/último do XML."""
        metadata = parse_call_xml(fixture_path("inbound_com_2_transferencias_sintetico.xml"))
        assert metadata.number_of_transfers == 2
        assert len(metadata.transfer_events) == 2

        first, second = metadata.transfer_events
        assert first.target_switch_call_id == "00001140131781525981"
        assert first.disconnected_by == "atendente"
        assert second.target_switch_call_id == "00001140131781529999"
        assert second.disconnected_by == "cliente"
        assert first.transferred_at < second.transferred_at

    def test_transferencia_sem_begin_call_anterior_nao_quebra(self):
        """Caso defensivo: se por algum motivo não houver Begin_Call antes de
        um Transferred, o evento ainda é emitido, só sem chave de correlação."""
        import xml.etree.ElementTree as ET
        from src.xml_parser import _extract_transfer_events, _NS

        xml_str = """<x:session xmlns:x="http://www.verint.com/xmlns/recording20080320">
          <x:tags>
            <x:tag x:timestamp="2026-01-01T00:00:00-03:00">
              <x:attribute x:key="disconnectingparty">OTHER</x:attribute>
              <x:attribute x:key="eventtype">Transferred</x:attribute>
            </x:tag>
          </x:tags>
        </x:session>"""
        session = ET.fromstring(xml_str)
        events = _extract_transfer_events(session)
        assert len(events) == 1
        assert events[0].target_switch_call_id is None
        assert events[0].disconnected_by == "cliente"


class TestCasosDeErro:
    def test_arquivo_inexistente_levanta_xmlparseerror(self):
        from src.xml_parser import XmlParseError
        with pytest.raises(XmlParseError):
            parse_call_xml(fixture_path("nao_existe.xml"))
