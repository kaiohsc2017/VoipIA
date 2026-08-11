"""
speech_field_formatter.py — formatação de campos de fala/texto do fluxo URA (Módulo 1).

Extraído de jira_call_flow.py (fase 22, O3.3 da refatoração) — funções puras de
texto→texto, sem I/O e sem estado de chamada, usadas para construir o hint enviado
ao STT e normalizar a transcrição recebida conforme o tipo de campo da pergunta.
"""

import re

# Padrões que indicam que o STT captou ruído/TTS em vez de voz humana real
NOISE_PATTERNS = (
    '[música', '[music', '[ruído', '[noise', '[silêncio', '[silence',
    '[sem fala', 'sem fala', 'não há fala', 'barulho de máquina',
    'música instrumental', '[audio', '[som', 'background',
)

# Mapa de palavras numéricas → dígito (BR Portuguese)
_NUMBER_WORDS: dict[str, str] = {
    "zero": "0", "um": "1", "uma": "1", "dois": "2", "duas": "2",
    "três": "3", "tres": "3", "quatro": "4", "cinco": "5",
    "seis": "6", "sete": "7", "oito": "8", "nove": "9",
}


def build_stt_hint(question_text: str, field_key: str, expected_values: str = "") -> str:
    """
    Monta o prompt de contexto enviado ao STT conforme o tipo de campo.

    O contexto reduz ambiguidade: o modelo sabe que deve transcrever
    um ramal (dígitos), um login (com ponto), tipo de ticket ou texto livre.
    """
    fk = field_key.lower()

    if any(k in fk for k in ("telefone", "ramal", "phone", "fone")):
        return (
            f"Contexto: {question_text}\n"
            "O usuário irá falar um número de ramal ou telefone dígito por dígito "
            "(ex: 'cinco zero zero quatro'). "
            "Transcreva APENAS os dígitos em algarismos, sem espaços (ex: 5004). "
            "Ignore palavras como 'ramal' ou 'número'. "
            "Retorne somente os dígitos."
        )

    if any(k in fk for k in ("nome", "login", "user", "email", "mail")):
        return (
            f"Contexto: {question_text}\n"
            "O usuário irá falar um login de rede no formato nome.sobrenome. "
            "Se disser 'ponto', escreva '.' (sem espaço). "
            "Exemplo: 'kaio ponto correa' → 'kaio.correa'. "
            "Retorne apenas o login, sem pontuação extra."
        )

    if any(k in fk for k in ("priority", "prioridade", "urgencia", "urgência")):
        return (
            f"Contexto: {question_text}\n"
            "O usuário irá falar a prioridade: Baixa, Média ou Alta. "
            "Transcreva exatamente a palavra de prioridade que foi dita. "
            "Normalize variações: 'media' → 'Média', 'alta urgencia' → 'Alta'."
        )

    # Tipo de ticket (incidente vs. requisição) — campo type_ticket, issuetype ou similar
    if any(k in fk for k in ("type_ticket", "issuetype", "tipo")) or (
        "type" in fk and not any(k in fk for k in ("telefone", "ramal", "nome", "login", "priority", "prioridade"))
    ):
        opts = expected_values if expected_values else "Incidente, Requisição"
        return (
            f"Contexto: {question_text}\n"
            f"O usuário deve escolher entre: {opts}. "
            "Transcreva exatamente uma das opções. "
            "Mapeie: 'problema', 'falha', 'parou', 'erro' → 'Incidente'; "
            "'solicitação', 'nova', 'acesso', 'instalação', 'serviço' → 'Requisição'. "
            "Retorne apenas a opção escolhida."
        )

    # Campo com valores esperados explícitos (qualquer campo configurado com expected_values)
    if expected_values:
        vals = [v.strip() for v in expected_values.split(",") if v.strip()]
        if vals:
            return (
                f"Contexto: {question_text}\n"
                f"O usuário deve responder com uma destas opções: {', '.join(vals)}. "
                "Transcreva exatamente uma das opções acima. "
                "Retorne apenas a opção escolhida."
            )

    return (
        f"Contexto: {question_text}\n"
        "Transcreva em português do Brasil exatamente o que foi dito. "
        "Retorne apenas o texto transcrito."
    )


def normalize_transcription(text: str, field_key: str, expected_values: str = "") -> str:
    """
    Normaliza a transcrição do STT para o campo específico.

    Converte palavras faladas em representações canônicas:
    - Ramais/telefones: palavras numéricas → dígitos, remove prefixo "ramal"
    - Logins: "ponto" → ".", remove espaços entre partes do login
    - Prioridades: normaliza capitalização
    """
    fk = field_key.lower()

    if any(k in fk for k in ("telefone", "ramal", "phone", "fone")):
        # Converte palavras numéricas para dígitos
        for word, digit in _NUMBER_WORDS.items():
            text = re.sub(rf'\b{word}\b', digit, text, flags=re.IGNORECASE)
        # Remove espaços entre dígitos consecutivos: "5 0 0 4" → "5004"
        text = re.sub(r'(?<=\d)\s+(?=\d)', '', text)
        # Remove prefixo "ramal " ou "número " se capturado
        text = re.sub(r'^(ramal|número|numero|tel|fone)\s*', '', text, flags=re.IGNORECASE)
        return text.strip()

    if any(k in fk for k in ("nome", "login", "user", "email", "mail")):
        # Converte "ponto" → "." (com ou sem espaços ao redor)
        text = re.sub(r'\s*\bponto\b\s*', '.', text, flags=re.IGNORECASE)
        # Remove espaços ao redor de pontos restantes: "kaio . correa" → "kaio.correa"
        text = re.sub(r'\s*\.\s*', '.', text)
        # Remove prefixo "login" ou "usuário" se capturado acidentalmente
        text = re.sub(r'^(login|usuário|usuario|user)\s*[:;]?\s*', '', text, flags=re.IGNORECASE)
        return text.strip()

    if any(k in fk for k in ("priority", "prioridade", "urgencia", "urgência")):
        tl = text.lower().strip()
        if "alta" in tl or "urgente" in tl or "crítica" in tl or "critica" in tl:
            return "Alta"
        if "média" in tl or "media" in tl or "moderada" in tl:
            return "Média"
        if "baixa" in tl or "menor" in tl:
            return "Baixa"
        return text

    # Tipo de ticket — normaliza para Incidente ou Requisição
    if any(k in fk for k in ("type_ticket", "issuetype", "tipo")) or (
        "type" in fk and not any(k in fk for k in ("telefone", "ramal", "nome", "login", "priority", "prioridade"))
    ):
        tl = text.lower().strip()
        if any(w in tl for w in ("incidente", "incident", "problema", "bug", "erro", "falha", "parou", "quebrou", "crítico", "critico")):
            return "Incidente"
        if any(w in tl for w in ("solicitação", "solicitacao", "requisição", "requisicao", "request", "nova", "novo", "serviço", "servico", "acesso", "instalação", "instalacao", "abertura")):
            return "Requisição"
        return text

    return text


def matches_expected(text: str, expected_values: str) -> bool:
    """True se `text` corresponde a uma das opções de expected_values (case-insensitive)."""
    vals = [v.strip().lower() for v in expected_values.split(",") if v.strip()]
    return text.strip().lower() in vals
