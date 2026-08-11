"""
test_masking.py — cobre o mascaramento de CPF/cartão/telefone na transcrição (Fase 8 do
módulo Call Center, aplicado a todas as origens do pipeline de Insights).
"""

from __future__ import annotations

from dataclasses import dataclass

from src.masking import mask_segments, mask_sensitive


class TestMaskCpf:
    def test_cpf_com_pontuacao_e_mascarado(self):
        assert mask_sensitive("meu CPF é 123.456.789-01, pode confirmar") == \
            "meu CPF é [CPF MASCARADO], pode confirmar"

    def test_cpf_sem_pontuacao_e_mascarado(self):
        assert mask_sensitive("CPF 12345678901 aqui") == "CPF [CPF MASCARADO] aqui"

    def test_cpf_com_espaco_em_vez_de_ponto_e_mascarado(self):
        # Formato mais comum vindo de transcrição por voz (STT raramente produz pontuação).
        assert mask_sensitive("meu CPF é 123 456 789 01 pode ser") == \
            "meu CPF é [CPF MASCARADO] pode ser"


class TestMaskCartao:
    def test_cartao_16_digitos_com_espacos_e_mascarado(self):
        texto = "número do cartão é 1234 5678 9012 3456 obrigado"
        assert mask_sensitive(texto) == "número do cartão é [CARTÃO MASCARADO] obrigado"

    def test_cartao_15_digitos_amex_e_mascarado(self):
        assert mask_sensitive("cartão 123456789012345 aqui") == "cartão [CARTÃO MASCARADO] aqui"


class TestMaskTelefone:
    def test_telefone_com_ddd_e_mascarado(self):
        assert mask_sensitive("me liga no (11) 98765-4321 por favor") == \
            "me liga no [TELEFONE MASCARADO] por favor"

    def test_telefone_sem_ddd_e_mascarado(self):
        assert mask_sensitive("o número é 98765-4321") == "o número é [TELEFONE MASCARADO]"

    def test_telefone_com_espaco_em_vez_de_hifen_e_mascarado(self):
        # Transcrição por voz raramente produz o hífen "escrito" entre os dois blocos.
        assert mask_sensitive("me liga no 98765 4321 por favor") == \
            "me liga no [TELEFONE MASCARADO] por favor"

    def test_telefone_landline_sem_pontuacao_e_mascarado(self):
        # DDD + 8 dígitos, tudo colado (sem parênteses/hífen) — ditado por voz e
        # transcrito assim é comum; regressão do achado do code-reviewer (gap real
        # onde o DDD ficava exposto antes do marcador).
        assert mask_sensitive("me chama no 1187654321 por favor") == \
            "me chama no [TELEFONE MASCARADO] por favor"


class TestSemDadoSensivel:
    def test_texto_sem_dado_sensivel_nao_e_alterado(self):
        texto = "bom dia, como posso ajudar você hoje"
        assert mask_sensitive(texto) == texto

    def test_texto_vazio_retorna_vazio(self):
        assert mask_sensitive("") == ""

    def test_texto_none_retorna_none(self):
        assert mask_sensitive(None) is None


@dataclass(frozen=True)
class _FakeSegment:
    speaker: str
    start_ms: int
    end_ms: int
    text: str
    tone_semantic: str


class TestMaskSegments:
    def test_mascara_texto_de_cada_segmento_preservando_os_demais_campos(self):
        segments = [
            _FakeSegment("cliente", 0, 1000, "meu CPF é 123.456.789-01", "neutro"),
            _FakeSegment("agente", 1000, 2000, "certo, um momento", "neutro"),
        ]

        masked = mask_segments(segments)

        assert masked[0].text == "meu CPF é [CPF MASCARADO]"
        assert masked[0].speaker == "cliente"
        assert masked[0].start_ms == 0
        assert masked[0].end_ms == 1000
        assert masked[1].text == "certo, um momento"


class TestOrdemDeAplicacao:
    def test_cpf_e_telefone_no_mesmo_texto_sao_mascarados_independentemente(self):
        texto = "CPF 123.456.789-01 e telefone (21) 91234-5678"
        resultado = mask_sensitive(texto)
        assert "[CPF MASCARADO]" in resultado
        assert "[TELEFONE MASCARADO]" in resultado
        assert "123.456.789-01" not in resultado
