"""
llm.py — Abstração de provedores LLM para o VoipIA Agentes

Configuração via arquivo dedicado /opt/VoipIA/env/.env.agents
(caminho configurável via AGENTS_ENV_FILE).

O arquivo .env.agents é lido em tempo de execução a cada chamada ao LLM,
permitindo alterações pelo painel web sem reiniciar o container.

Variáveis suportadas:
  AGENTS_LLM_PROVIDER     = google | anthropic | openai | minimax | openai_compat
  AGENTS_LLM_MODEL        = gemini-2.5-flash | claude-sonnet-4-6 | gpt-4o-mini | ...
  AGENTS_LLM_ENABLED      = true | false   (opt-in — false por padrão)
  AGENTS_LLM_GOOGLE_KEY   = AIza...
  AGENTS_LLM_ANTHROPIC_KEY= sk-ant-...
  AGENTS_LLM_OPENAI_KEY   = sk-...
  AGENTS_LLM_MINIMAX_KEY  = ...
  AGENTS_LLM_MINIMAX_GROUP_ID = ...
  AGENTS_LLM_COMPAT_URL   = http://localhost:11434/v1
  AGENTS_LLM_COMPAT_KEY   = (opcional)
"""

import os, aiohttp, logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger("asteriskia.llm")

# ─── Caminho do arquivo dedicado ─────────────────────────────────────────────

ENV_FILE = Path(os.environ.get(
    "AGENTS_ENV_FILE",
    "/opt/VoipIA/env/.env.agents"
))

ENV_KEYS = [
    "AGENTS_LLM_ENABLED",
    "AGENTS_LLM_PROVIDER",
    "AGENTS_LLM_MODEL",
    "AGENTS_LLM_GOOGLE_KEY",
    "AGENTS_LLM_ANTHROPIC_KEY",
    "AGENTS_LLM_OPENAI_KEY",
    "AGENTS_LLM_MINIMAX_KEY",
    "AGENTS_LLM_MINIMAX_GROUP_ID",
    "AGENTS_LLM_COMPAT_URL",
    "AGENTS_LLM_COMPAT_KEY",
]

PROVIDERS_CATALOG = [
    {
        "id": "google",
        "name": "Google Gemini",
        "key_var": "AGENTS_LLM_GOOGLE_KEY",
        "models": [
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        ],
        "docs": "https://aistudio.google.com",
    },
    {
        "id": "anthropic",
        "name": "Anthropic Claude",
        "key_var": "AGENTS_LLM_ANTHROPIC_KEY",
        "models": [
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001",
            "claude-opus-4-6",
        ],
        "docs": "https://console.anthropic.com",
    },
    {
        "id": "openai",
        "name": "OpenAI GPT",
        "key_var": "AGENTS_LLM_OPENAI_KEY",
        "models": [
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4-turbo",
            "o1-mini",
        ],
        "docs": "https://platform.openai.com",
    },
    {
        "id": "minimax",
        "name": "MiniMax",
        "key_var": "AGENTS_LLM_MINIMAX_KEY",
        "models": [
            "abab6.5s-chat",
            "abab6.5g-chat",
            "abab5.5s-chat",
        ],
        "docs": "https://www.minimax.chat",
    },
    {
        "id": "openai_compat",
        "name": "API Compatível OpenAI",
        "key_var": "AGENTS_LLM_COMPAT_URL",
        "models": [
            "llama3.2",
            "mistral",
            "llama-3.3-70b-versatile",
        ],
        "docs": "https://github.com/ollama/ollama",
    },
]

# ─── Leitura/escrita do .env.agents ──────────────────────────────────────────

def read_env_file() -> dict:
    """Lê o .env.agents e retorna dict com os valores atuais."""
    values = {k: "" for k in ENV_KEYS}
    # Defaults
    values["AGENTS_LLM_ENABLED"]   = "false"
    values["AGENTS_LLM_PROVIDER"]  = "google"
    values["AGENTS_LLM_MODEL"]     = "gemini-2.5-flash"
    values["AGENTS_LLM_COMPAT_URL"] = "http://localhost:11434/v1"

    if not ENV_FILE.exists():
        return values

    for raw in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        v = v.strip().strip('"').strip("'")
        if k in values:
            values[k] = v

    return values

def write_env_file(values: dict) -> None:
    """Escreve o .env.agents com os novos valores."""
    ENV_FILE.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# VoipIA Agentes — Configuração de IA",
        "# Editado pelo painel web — não edite manualmente enquanto o sistema estiver rodando",
        "",
    ]
    labels = {
        "AGENTS_LLM_ENABLED":        "Habilita IA globalmente (true/false — opt-in)",
        "AGENTS_LLM_PROVIDER":        "Provedor ativo: google | anthropic | openai | minimax | openai_compat",
        "AGENTS_LLM_MODEL":           "Modelo do provedor ativo",
        "AGENTS_LLM_GOOGLE_KEY":      "API key Google (aistudio.google.com)",
        "AGENTS_LLM_ANTHROPIC_KEY":   "API key Anthropic (console.anthropic.com)",
        "AGENTS_LLM_OPENAI_KEY":      "API key OpenAI (platform.openai.com)",
        "AGENTS_LLM_MINIMAX_KEY":     "API key MiniMax",
        "AGENTS_LLM_MINIMAX_GROUP_ID":"Group ID MiniMax",
        "AGENTS_LLM_COMPAT_URL":      "URL base API compatível OpenAI (Ollama, Groq...)",
        "AGENTS_LLM_COMPAT_KEY":      "API key compatível (opcional)",
    }
    for key in ENV_KEYS:
        val = values.get(key, "")
        lines.append(f"# {labels.get(key, key)}")
        lines.append(f"{key}={val}")
        lines.append("")

    ENV_FILE.write_text("\n".join(lines), encoding="utf-8")

def reload_config():
    """Recarrega a configuração do .env.agents — chamado após salvar pelo painel."""
    values = read_env_file()
    cfg.provider  = values.get("AGENTS_LLM_PROVIDER", "google").lower()
    cfg.model     = values.get("AGENTS_LLM_MODEL", "gemini-2.5-flash")
    cfg.enabled   = values.get("AGENTS_LLM_ENABLED", "false").lower() in ("true","1","yes","sim")
    cfg.google_key     = values.get("AGENTS_LLM_GOOGLE_KEY", "") or os.environ.get("GEMINI_API_KEY","")
    cfg.anthropic_key  = values.get("AGENTS_LLM_ANTHROPIC_KEY","")
    cfg.openai_key     = values.get("AGENTS_LLM_OPENAI_KEY","")
    cfg.minimax_key    = values.get("AGENTS_LLM_MINIMAX_KEY","")
    cfg.minimax_group  = values.get("AGENTS_LLM_MINIMAX_GROUP_ID","")
    cfg.compat_url     = values.get("AGENTS_LLM_COMPAT_URL","http://localhost:11434/v1")
    cfg.compat_key     = values.get("AGENTS_LLM_COMPAT_KEY","")

# ─── Config ───────────────────────────────────────────────────────────────────

class LLMConfig:
    def __init__(self):
        # Carrega do .env.agents; fallback para variáveis de ambiente do container
        values = read_env_file()
        self.provider  = values.get("AGENTS_LLM_PROVIDER", os.environ.get("AGENTS_LLM_PROVIDER","google")).lower()
        self.model     = values.get("AGENTS_LLM_MODEL",    os.environ.get("AGENTS_LLM_MODEL","gemini-2.5-flash"))
        self.enabled   = values.get("AGENTS_LLM_ENABLED",  os.environ.get("AGENTS_LLM_ENABLED","false")).lower() in ("true","1","yes","sim")
        self.google_key     = values.get("AGENTS_LLM_GOOGLE_KEY","")     or os.environ.get("GEMINI_API_KEY","")
        self.anthropic_key  = values.get("AGENTS_LLM_ANTHROPIC_KEY","")  or os.environ.get("AGENTS_LLM_ANTHROPIC_KEY","")
        self.openai_key     = values.get("AGENTS_LLM_OPENAI_KEY","")     or os.environ.get("AGENTS_LLM_OPENAI_KEY","")
        self.minimax_key    = values.get("AGENTS_LLM_MINIMAX_KEY","")    or os.environ.get("AGENTS_LLM_MINIMAX_KEY","")
        self.minimax_group  = values.get("AGENTS_LLM_MINIMAX_GROUP_ID","") or os.environ.get("AGENTS_LLM_MINIMAX_GROUP_ID","")
        self.compat_url     = values.get("AGENTS_LLM_COMPAT_URL","")     or os.environ.get("AGENTS_LLM_COMPAT_URL","http://localhost:11434/v1")
        self.compat_key     = values.get("AGENTS_LLM_COMPAT_KEY","")     or os.environ.get("AGENTS_LLM_COMPAT_KEY","")

    def is_ready(self) -> tuple[bool, str]:
        if not self.enabled:
            return False, "IA desabilitada — ative em Configuração de IA"
        keys = {
            "google":        self.google_key,
            "anthropic":     self.anthropic_key,
            "openai":        self.openai_key,
            "minimax":       self.minimax_key,
            "openai_compat": True,
        }
        if self.provider not in keys:
            return False, f"Provedor '{self.provider}' não suportado"
        if not keys[self.provider]:
            return False, f"API key não configurada para '{self.provider}'"
        return True, "ok"

    def summary(self) -> dict:
        ok, reason = self.is_ready()
        return {
            "enabled":   self.enabled,
            "provider":  self.provider,
            "model":     self.model,
            "ready":     ok,
            "reason":    reason if not ok else "",
            "env_file":  str(ENV_FILE),
            "file_exists": ENV_FILE.exists(),
        }

cfg = LLMConfig()

# ─── Chamadas por provedor ────────────────────────────────────────────────────

async def _call_google(prompt: str) -> str:
    # Achado de segurança (CRITICAL): a key ia na query string — qualquer erro
    # ≠429 ecoa a URL completa via str(e) (aiohttp.ClientResponseError inclui
    # a URL da requisição). Header x-goog-api-key nunca aparece em str(e).
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{cfg.model}:generateContent"
    async with aiohttp.ClientSession() as s:
        async with s.post(url, json={"contents":[{"parts":[{"text":prompt}]}]},
                          headers={"x-goog-api-key": cfg.google_key},
                          timeout=aiohttp.ClientTimeout(total=30)) as r:
            if r.status == 429:
                raise RuntimeError("Limite de gastos atingido (429) — verifique aistudio.google.com/spend")
            r.raise_for_status()
            data = await r.json()
            return data["candidates"][0]["content"]["parts"][0]["text"]

async def _call_anthropic(prompt: str) -> str:
    async with aiohttp.ClientSession() as s:
        async with s.post(
            "https://api.anthropic.com/v1/messages",
            headers={"x-api-key":cfg.anthropic_key,"anthropic-version":"2023-06-01","content-type":"application/json"},
            json={"model":cfg.model,"max_tokens":1024,"messages":[{"role":"user","content":prompt}]},
            timeout=aiohttp.ClientTimeout(total=30)) as r:
            r.raise_for_status()
            data = await r.json()
            return data["content"][0]["text"]

async def _call_openai(prompt: str, base_url: str = "https://api.openai.com/v1",
                       api_key: Optional[str] = None) -> str:
    key = api_key or cfg.openai_key
    async with aiohttp.ClientSession() as s:
        async with s.post(
            f"{base_url.rstrip('/')}/chat/completions",
            headers={"Authorization":f"Bearer {key}","Content-Type":"application/json"},
            json={"model":cfg.model,"messages":[{"role":"user","content":prompt}]},
            timeout=aiohttp.ClientTimeout(total=30)) as r:
            r.raise_for_status()
            data = await r.json()
            return data["choices"][0]["message"]["content"]

async def _call_minimax(prompt: str) -> str:
    async with aiohttp.ClientSession() as s:
        async with s.post(
            f"https://api.minimax.chat/v1/text/chatcompletion_v2?GroupId={cfg.minimax_group}",
            headers={"Authorization":f"Bearer {cfg.minimax_key}","Content-Type":"application/json"},
            json={"model":cfg.model or "abab6.5s-chat","messages":[{"role":"user","content":prompt}]},
            timeout=aiohttp.ClientTimeout(total=30)) as r:
            r.raise_for_status()
            data = await r.json()
            return data["choices"][0]["message"]["content"]

# ─── Ponto de entrada principal ───────────────────────────────────────────────

async def ask(skill: str, problem: str, memory_ctx: str, check_output: str) -> str:
    ok, reason = cfg.is_ready()
    if not ok:
        return f"[IA desabilitada: {reason}]"

    prompt = f"""Você é um especialista em infraestrutura Linux com o seguinte contexto:
{skill}

Ocorreu uma falha durante uma verificação automatizada.

SAÍDA DO COMANDO / RESULTADO:
{check_output[:1000]}

PROBLEMA IDENTIFICADO:
{problem}

CONTEXTO DE MEMÓRIA E DOCUMENTAÇÃO:
{memory_ctx[:2000] if memory_ctx else 'Nenhum contexto disponível.'}

Forneça:
1. Diagnóstico da causa mais provável (máximo 2 frases)
2. Comando(s) exato(s) para corrigir
3. Como prevenir no futuro

Seja direto e técnico. Responda em português."""

    try:
        if cfg.provider == "google":
            return await _call_google(prompt)
        elif cfg.provider == "anthropic":
            return await _call_anthropic(prompt)
        elif cfg.provider == "openai":
            return await _call_openai(prompt)
        elif cfg.provider == "minimax":
            return await _call_minimax(prompt)
        elif cfg.provider == "openai_compat":
            return await _call_openai(prompt, base_url=cfg.compat_url,
                                       api_key=cfg.compat_key or None)
        else:
            return f"[Provedor '{cfg.provider}' não suportado]"
    except Exception as e:
        # Achado de segurança (CRITICAL): repassar str(e) bruto ao usuário já
        # vazou a API key do Google via query string; essa string também é
        # persistida em agent_memory/execution_logs indefinidamente. Detalhe
        # completo só no log do servidor.
        logger.error("[llm] erro ao chamar %s/%s: %s", cfg.provider, cfg.model, e)
        return f"[Erro IA ({cfg.provider}/{cfg.model}) — ver logs do backend para detalhes]"

def is_enabled() -> bool:
    ok, _ = cfg.is_ready()
    return ok
