"""
gemini_service.py — Serviço de IA via Google Gemini (SDK google-genai)

Encapsula STT (Speech-to-Text), LLM com Function Calling e TTS (Text-to-Speech).

Formatos de áudio:
  - Entrada (STT): PCM 8kHz 16bit signed little-endian mono (formato Asterisk)
  - Saída (TTS):   PCM 8kHz 16bit signed little-endian mono (formato Asterisk)

Function Calling (Gemini Tools):
  O Gemini pode invocar funções locais durante uma conversa para buscar dados
  em tempo real (ex: status de pedido, consulta de saldo, protocolo de atendimento).
  O ciclo é: LLM → identifica intenção → tool_call → executa função local → 
  resposta final com dados reais.

Nota: usa o novo SDK `google-genai` (pip install google-genai),
não o legado `google-generativeai`.
"""

import io
import json
import logging
import asyncio
import struct
from typing import Any
import numpy as np
import google.genai as genai
import google.genai.types as genai_types
from src.config import GEMINI_API_KEY, GEMINI_MODEL_STT, GEMINI_MODEL_LLM, GEMINI_MODEL_TTS

logger = logging.getLogger("asteriskia.gemini")


# ─── Tool definitions (declaradas para o Gemini) ────────────────────────────────

_TOOLS = genai_types.Tool(
    function_declarations=[
        genai_types.FunctionDeclaration(
            name="consultar_status_pedido",
            description=(
                "Consulta o status de um pedido pelo número de protocolo ou CPF do cliente. "
                "Use quando o cliente mencionar um protocolo, número de pedido ou seu CPF."
            ),
            parameters=genai_types.Schema(
                type=genai_types.Type.OBJECT,
                properties={
                    "identificador": genai_types.Schema(
                        type=genai_types.Type.STRING,
                        description="CPF (somente números) ou número de protocolo do pedido",
                    )
                },
                required=["identificador"],
            ),
        ),
        genai_types.FunctionDeclaration(
            name="abrir_protocolo_suporte",
            description=(
                "Abre um novo protocolo de suporte para o cliente. "
                "Use quando o cliente solicitar abertura de chamado ou suporte técnico."
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


# Em produção, chamadas REST reais ao backend.

_protocolo_counter = 3000


def _execute_tool(tool_name: str, args: dict[str, Any]) -> str:
    """
    Executa a função solicitada pelo Gemini e retorna o resultado como string JSON.
    Esta é a camada de integração entre o LLM e o mundo real.
    """
    global _protocolo_counter

    if tool_name == "consultar_status_pedido":
        ident = args.get("identificador", "").strip().replace(".", "").replace("-", "")
        try:
            import requests
            from src.config import BACKEND_URL, INTERNAL_API_KEY
            headers = {"X-Internal-Key": INTERNAL_API_KEY, "Content-Type": "application/json"}
            
            resp = requests.get(f"{BACKEND_URL}/api/v1/pedidos/{ident}", headers=headers, timeout=10.0)
            if resp.status_code == 200:
                pedido = resp.json()
                return json.dumps({
                    "encontrado": True,
                    "protocolo": pedido.get("protocolo", "N/A"),
                    "produto": pedido.get("produto", "N/A"),
                    "status": pedido.get("status", "N/A"),
                    "previsao_entrega": pedido.get("previsao", "N/A"),
                    "transportadora": pedido.get("transportadora", "N/A"),
                }, ensure_ascii=False)
            else:
                return json.dumps({
                    "encontrado": False,
                    "mensagem": f"Não foi encontrado nenhum pedido para o identificador '{ident}'.",
                }, ensure_ascii=False)
        except Exception as e:
            logger.error("Erro na chamada REST consultar_status_pedido: %s", e)
            return json.dumps({
                "encontrado": False,
                "mensagem": f"Sistema indisponível no momento para consultar o pedido '{ident}'.",
            }, ensure_ascii=False)

    elif tool_name == "abrir_protocolo_suporte":
        _protocolo_counter += 1
        protocolo = f"SUP-{_protocolo_counter}"
        descricao = args.get("descricao", "Sem descrição")
        prioridade = args.get("prioridade", "MEDIA")
        logger.info("Protocolo de suporte aberto: %s — %s [%s]", protocolo, descricao, prioridade)
        return json.dumps({
            "sucesso": True,
            "protocolo": protocolo,
            "descricao": descricao,
            "prioridade": prioridade,
            "mensagem": f"Protocolo {protocolo} aberto com sucesso. Nossa equipe entrará em contato em até 2 horas úteis.",
        }, ensure_ascii=False)

    return json.dumps({"erro": f"Função '{tool_name}' não reconhecida."})


class GeminiService:
    """
    Serviço centralizado de IA usando Google Gemini.

    Métodos públicos:
      - transcribe(pcm_data: bytes) -> str
      - synthesize_speech(text: str) -> bytes
      - generate_response(prompt: str, context: str) -> str
      - generate_response_with_tools(system: str, history: list) -> str  [com Function Calling]
    """

    def __init__(self):
        self._client = genai.Client(api_key=GEMINI_API_KEY)

    async def transcribe(self, pcm_data: bytes) -> str:
        """
        Converte áudio PCM (formato Asterisk) em texto via Gemini STT.

        Args:
            pcm_data: Áudio bruto PCM 8kHz/16bit/mono do Asterisk

        Returns:
            Texto transcrito ou string vazia em caso de erro
        """
        try:
            wav_bytes = _pcm_to_wav(pcm_data, sample_rate=8000)
            result = await asyncio.to_thread(self._transcribe_sync, wav_bytes)
            return result.strip()
        except Exception as e:
            logger.error("Erro no STT: %s", e)
            return ""

    async def synthesize_speech(self, text: str) -> bytes:
        """
        Converte texto em áudio PCM (formato Asterisk) via Gemini TTS.

        Args:
            text: Texto a ser sintetizado em voz

        Returns:
            Bytes de áudio PCM 8kHz/16bit/mono compatível com Asterisk
        """
        try:
            return await asyncio.to_thread(self._tts_sync, text)
        except Exception as e:
            logger.error("Erro no TTS: %s", e)
            return b'\x00' * 320  # silêncio (20ms @ 8kHz)

    async def generate_response(self, prompt: str, context: str = "") -> str:
        """
        Gera uma resposta de texto via Gemini LLM (sem Function Calling).
        Usado para respostas simples de mapeamento de campos.
        """
        try:
            full_prompt = f"{context}\n\n{prompt}" if context else prompt
            return (await asyncio.to_thread(self._llm_sync, full_prompt)).strip()
        except Exception as e:
            logger.error("Erro no LLM: %s", e)
            return ""

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict[str, str]],
    ) -> str:
        """
        Gera uma resposta via Gemini com Function Calling habilitado.

        O ciclo completo:
          1. Envia histórico + tools ao Gemini
          2. Se o modelo retornar uma function_call → executa localmente
          3. Envia o resultado de volta ao Gemini para gerar resposta final em texto
          4. Retorna o texto final

        Args:
            system_instruction: Prompt de sistema (papel do agente)
            history: Lista de turnos [{"role": "user"|"model", "text": "..."}]

        Returns:
            Resposta final do Gemini (texto puro, já com os dados da função)
        """
        try:
            return await asyncio.to_thread(
                self._llm_with_tools_sync, system_instruction, history
            )
        except Exception as e:
            logger.error("Erro no LLM+Tools: %s", e)
            return ""

    # ------------------------------------------------------------------
    # Métodos privados síncronos (executados em thread pool)
    # ------------------------------------------------------------------

    def _transcribe_sync(self, wav_bytes: bytes) -> str:
        """STT via Gemini — envia WAV e retorna transcrição."""
        response = self._client.models.generate_content(
            model=GEMINI_MODEL_STT,
            contents=[
                genai_types.Content(parts=[
                    genai_types.Part(text=(
                        "Transcreva exatamente o que foi dito neste áudio em português do Brasil. "
                        "Retorne apenas o texto transcrito, sem pontuação adicional."
                    )),
                    genai_types.Part(inline_data=genai_types.Blob(
                        mime_type="audio/wav",
                        data=wav_bytes
                    )),
                ])
            ]
        )
        return response.text or ""

    def _tts_sync(self, text: str) -> bytes:
        """
        TTS via Gemini — retorna áudio PCM 8kHz/16bit/mono.
        O modelo retorna PCM L16 24kHz; fazemos resample para 8kHz (Asterisk).
        """
        response = self._client.models.generate_content(
            model=GEMINI_MODEL_TTS,
            contents=text,
            config=genai_types.GenerateContentConfig(
                response_modalities=["AUDIO"],
                speech_config=genai_types.SpeechConfig(
                    voice_config=genai_types.VoiceConfig(
                        prebuilt_voice_config=genai_types.PrebuiltVoiceConfig(
                            voice_name="Aoede"
                        )
                    )
                )
            )
        )
        audio_data = response.candidates[0].content.parts[0].inline_data.data
        return _resample_pcm(audio_data, from_hz=24000, to_hz=8000)

    def _llm_sync(self, prompt: str) -> str:
        """LLM via Gemini — gera resposta de texto simples (sem tools)."""
        response = self._client.models.generate_content(
            model=GEMINI_MODEL_LLM,
            contents=prompt,
        )
        return response.text or ""

    def _llm_with_tools_sync(
        self,
        system_instruction: str,
        history: list[dict[str, str]],
    ) -> str:
        """
        LLM com Function Calling — ciclo completo em modo síncrono.
        Suporta múltiplos turnos de function_call antes da resposta final.
        """
        # Converte histórico para o formato genai_types.Content
        contents: list[genai_types.Content] = []
        for turn in history:
            role = turn["role"]  # "user" ou "model"
            contents.append(genai_types.Content(
                role=role,
                parts=[genai_types.Part(text=turn["text"])]
            ))

        config = genai_types.GenerateContentConfig(
            system_instruction=system_instruction,
            tools=[_TOOLS],
            tool_config=genai_types.ToolConfig(
                function_calling_config=genai_types.FunctionCallingConfig(
                    mode=genai_types.FunctionCallingConfig.Mode.AUTO,
                )
            ),
        )

        # Ciclo de agentic loop (máx. 5 iterações para evitar loops infinitos)
        for _ in range(5):
            response = self._client.models.generate_content(
                model=GEMINI_MODEL_LLM,
                contents=contents,
                config=config,
            )

            candidate = response.candidates[0]

            # Verifica se há function_call(s) na resposta
            tool_calls = [
                p.function_call
                for p in candidate.content.parts
                if p.function_call is not None
            ]

            if not tool_calls:
                # Sem function_call — resposta final de texto
                return response.text or ""

            # Adiciona a resposta do modelo ao histórico
            contents.append(candidate.content)

            # Executa cada função e adiciona os resultados ao histórico
            function_results = []
            for fc in tool_calls:
                logger.info("Gemini solicitou função: %s(%s)", fc.name, fc.args)
                result_str = _execute_tool(fc.name, dict(fc.args))
                logger.info("Resultado da função %s: %s", fc.name, result_str)

                function_results.append(
                    genai_types.Part(
                        function_response=genai_types.FunctionResponse(
                            name=fc.name,
                            response={"result": result_str},
                        )
                    )
                )

            contents.append(genai_types.Content(
                role="user",
                parts=function_results,
            ))

        logger.warning("Máximo de iterações atingido no agentic loop")
        return "Desculpe, não consegui processar sua solicitação no momento."


# ------------------------------------------------------------------
# Funções utilitárias de conversão de áudio
# (sem dependência de soundfile — usa apenas numpy + stdlib)
# ------------------------------------------------------------------

def _pcm_to_wav(pcm_data: bytes, sample_rate: int = 8000) -> bytes:
    """
    Converte PCM 16bit little-endian para WAV em memória.
    Usa apenas stdlib struct (sem soundfile).
    """
    num_channels = 1
    bits_per_sample = 16
    byte_rate = sample_rate * num_channels * bits_per_sample // 8
    block_align = num_channels * bits_per_sample // 8
    data_size = len(pcm_data)
    header_size = 44

    buf = io.BytesIO()
    buf.write(b'RIFF')
    buf.write(struct.pack('<I', header_size - 8 + data_size))
    buf.write(b'WAVE')
    buf.write(b'fmt ')
    buf.write(struct.pack('<I', 16))
    buf.write(struct.pack('<H', 1))
    buf.write(struct.pack('<H', num_channels))
    buf.write(struct.pack('<I', sample_rate))
    buf.write(struct.pack('<I', byte_rate))
    buf.write(struct.pack('<H', block_align))
    buf.write(struct.pack('<H', bits_per_sample))
    buf.write(b'data')
    buf.write(struct.pack('<I', data_size))
    buf.write(pcm_data)

    return buf.getvalue()


def _resample_pcm(pcm_data: bytes, from_hz: int, to_hz: int) -> bytes:
    """
    Resample PCM 16bit little-endian de from_hz para to_hz.
    Usa interpolação linear via numpy (sem scipy).
    """
    if from_hz == to_hz:
        return pcm_data

    samples = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)
    num_out = int(len(samples) * to_hz / from_hz)
    x_old = np.linspace(0, 1, len(samples))
    x_new = np.linspace(0, 1, num_out)
    resampled = np.interp(x_new, x_old, samples)
    return resampled.astype(np.int16).tobytes()
