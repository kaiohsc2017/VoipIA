"""
providers/gemini_shared.py — Cliente Gemini singleton, tools e function-calling
compartilhados por providers/gemini.py.

Extraído de services/gemini_service.py (fase de refatoração ONDA 2) — a classe
GeminiService daquele módulo era código morto (nunca instanciada); só estes
símbolos de módulo eram efetivamente usados por providers/gemini.py.
"""
import asyncio
import json
import logging
from typing import Any

import google.genai as genai
import google.genai.types as genai_types
import httpx

from src.services import backend_client

logger = logging.getLogger("asteriskia.gemini")


# ─── Tool: abrir_protocolo_suporte ───────────────────────────────────────────
# Integração real: abrir_protocolo_suporte → backend → JiraIntegrationService.
TOOLS = genai_types.Tool(
    function_declarations=[
        genai_types.FunctionDeclaration(
            name="abrir_protocolo_suporte",
            description=(
                "Abre um novo chamado no Jira para o cliente. "
                "Use quando o cliente solicitar abertura de chamado, suporte técnico "
                "ou registrar um problema. Retorna o número do protocolo gerado."
            ),
            parameters=genai_types.Schema(
                type=genai_types.Type.OBJECT,
                properties={
                    "descricao": genai_types.Schema(
                        type=genai_types.Type.STRING,
                        description="Descrição resumida do problema informado pelo cliente",
                    ),
                    "prioridade": genai_types.Schema(
                        type=genai_types.Type.STRING,
                        enum=["BAIXA", "MEDIA", "ALTA", "CRITICA"],
                        description="Prioridade do chamado",
                    ),
                },
                required=["descricao"],
            ),
        ),
    ]
)


def execute_tool(tool_name: str, args: dict[str, Any], loop: asyncio.AbstractEventLoop) -> str:
    """
    Executa a função solicitada pelo Gemini e retorna o resultado como string JSON.

    Roda em thread separada (via asyncio.to_thread no chamador), então reusa o
    backend_client — que é assíncrono — agendando a chamada de volta no event
    loop original com run_coroutine_threadsafe.
    """
    if tool_name == "abrir_protocolo_suporte":
        descricao = args.get("descricao", "Sem descrição")
        prioridade = args.get("prioridade", "MEDIA")
        payload = {"descricao": descricao, "prioridade": prioridade}

        try:
            future = asyncio.run_coroutine_threadsafe(
                backend_client.post("/api/v1/suporte/abrir", json=payload), loop
            )
            result = future.result(timeout=10.0)
            logger.info("Protocolo de suporte aberto (backend): %s", result.get("protocolo"))
            return json.dumps(result, ensure_ascii=False)
        except httpx.HTTPStatusError as e:
            logger.error("Erro do backend ao abrir protocolo: %s", e.response.text)
            return json.dumps({"erro": "Erro do sistema ao abrir o protocolo.", "sucesso": False}, ensure_ascii=False)
        except Exception as e:
            logger.error("Falha na chamada REST abrir_protocolo_suporte: %s", e)
            return json.dumps({"erro": "Sistema indisponível no momento.", "sucesso": False}, ensure_ascii=False)

    return json.dumps({"erro": f"Função '{tool_name}' não reconhecida."})


# Singleton do cliente Gemini — compartilhado entre todas as chamadas do processo.
# Evita overhead de criação de sessão HTTP a cada chamada e o erro "Cannot send
# a request, as the client has been closed".
_gemini_client_instance: "genai.Client | None" = None
_gemini_client_api_key: str = ""


def get_global_client() -> genai.Client:
    """Retorna o cliente Gemini singleton do processo, recriando apenas se a API key mudar."""
    global _gemini_client_instance, _gemini_client_api_key
    from src.config import get_gemini_api_key

    api_key = get_gemini_api_key()
    if not api_key:
        raise RuntimeError(
            "GEMINI_API_KEY não configurada. Acesse Settings → Google Gemini para configurar."
        )
    if _gemini_client_instance is None or _gemini_client_api_key != api_key:
        _gemini_client_instance = genai.Client(api_key=api_key)
        _gemini_client_api_key = api_key
        logger.info("Cliente Gemini (re)criado — API key atualizada.")
    return _gemini_client_instance
