"""
masking.py — Mascaramento de dado sensível na transcrição (Fase 8 do Call Center, mas
aplicado a todas as origens: verint/upload/callcenter compartilham o mesmo pipeline e o
mesmo risco de exposição de CPF/cartão/telefone ditados durante o atendimento).

Roda ANTES de persistir/enviar a transcrição ao backend — o texto mascarado é o único que
chega a call_transcript_segments.text; o áudio original não é alterado (mascarar o PCM
exigiria detectar o intervalo de tempo exato da fala, fora de escopo desta entrega).
"""

from __future__ import annotations

import dataclasses
import re

# Cartão de crédito: 13-19 dígitos, aceitando espaço/hífen como separador a cada 4 (a maioria
# das bandeiras usa 16, Amex usa 15) — checado ANTES de CPF/telefone pra não ser mascarado só
# parcialmente por um padrão mais curto. Cada separador exige um dígito depois (nunca no fim
# do match), senão o \b final consumiria o espaço/hífen seguinte junto com o mascaramento.
_CARD_RE = re.compile(r"\b\d(?:[ -]?\d){12,18}\b")

# CPF: 000.000.000-00 / 000 000 000 00 / 00000000000 (11 dígitos seguidos, separador
# opcional por ponto OU espaço — texto ditado por voz e transcrito pelo STT tende a sair
# com espaço em vez de pontuação, caso mais comum nesta feature do que input digitado).
_CPF_RE = re.compile(r"\b\d{3}[ .]?\d{3}[ .]?\d{3}[ -]?\d{2}\b")

# Telefone BR: (00) 00000-0000 / 00 00000-0000 / 0000000000 / 00987654321 / "98765 4321"
# sem pontuação (10 ou 11 dígitos, DDD opcional entre parênteses OU só colado à frente) —
# verificado por último (o mais genérico), depois que cartão/CPF já consumiram os padrões
# mais específicos. (?<!\d) no lugar de \b inicial: um \b logo antes de "(" não caia numa
# fronteira válida (parêntese e espaço são os dois não-palavra), o que faria o grupo de DDD
# entre parênteses nunca casar. Separador principal aceita espaço além de hífen — mesma
# razão do CPF acima (fala transcrita raramente produz o hífen "escrito").
_PHONE_RE = re.compile(r"(?<!\d)(?:\(\d{2}\)\s?|\d{2}[ -]?)?\d{4,5}[ -]?\d{4}\b")


def mask_sensitive(text: str) -> str:
    """Substitui CPF/cartão/telefone encontrados em `text` por um marcador fixo, preservando
    o restante do texto. Ordem importa: cartão (mais dígitos) antes de CPF/telefone, senão um
    número de cartão ditado em blocos poderia ser mascarado só parcialmente."""
    if not text:
        return text
    masked = _CARD_RE.sub("[CARTÃO MASCARADO]", text)
    masked = _CPF_RE.sub("[CPF MASCARADO]", masked)
    masked = _PHONE_RE.sub("[TELEFONE MASCARADO]", masked)
    return masked


def mask_segments(segments: list) -> list:
    """Mascara o texto de cada segmento ANTES de qualquer análise por LLM (diarização já
    concluída, tom acústico usa só start_ms/end_ms do áudio — nenhum dos dois depende do
    texto cru). Aplicar aqui, e não só na hora de montar o payload, garante que o LLM
    nunca veja o dado sensível — sem isso, achados que citam um trecho literal da
    transcrição (trecho_referencia) ou o resumo/insights_json podiam expor CPF/cartão/
    telefone mesmo com segments_payload mascarado (o LLM ecoa o texto de entrada, que
    chegava cru)."""
    return [dataclasses.replace(seg, text=mask_sensitive(seg.text)) for seg in segments]
