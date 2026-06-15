"""
llm.py — Abstração de provedores LLM para o AsteriskIA Agentes

Provedores suportados:
  - google    : Gemini (generativelanguage.googleapis.com)
  - anthropic : Claude (api.anthropic.com)
  - openai    : GPT (api.openai.com)
  - minimax   : MiniMax (api.minimax.chat)
  - openai_compat: qualquer API compatível com OpenAI (Ollama, Groq, Together, etc.)

Configuração via variáveis de ambiente no .env:
  AGENTS_LLM_PROVIDER=google          # provedor ativo
  AGENTS_LLM_MODEL=gemini-2.5-flash   # modelo ativo
  AGENTS_LLM_ENABLED=true             # habilita IA globalmente (opt-in)

  AGENTS_LLM_GOOGLE_KEY=AIza...
  AGENTS_LLM_ANTHROPIC_KEY=sk-ant-...
  AGENTS_LLM_OPENAI_KEY=sk-...
  AGENTS_LLM_MINIMAX_KEY=...
  AGENTS_LLM_MINIMAX_GROUP_ID=...
  AGENTS_LLM_COMPAT_URL=http://localhost:11434/v1   # Ollama, Groq, etc.
  AGENTS_LLM_COMPAT_KEY=optional-key
"""

import os, aiohttp, json
from typing import Optional

# ─── Configuração ─────────────────────────────────────────────────────────────

def _env(key: str, default: str = "") -> str:
    return os.environ.get(key, default).strip()

class LLMConfig:
    """Lê configuração do ambiente uma vez na inicialização."""

    def __init__(self):
        self.provider  = _env("AGENTS_LLM_PROVIDER", "google").lower()
        self.model     = _env("AGENTS_LLM_MODEL", "gemini-2.5-flash")
        self.enabled   = _env("AGENTS_LLM_ENABLED", "false").lower() in ("true","1","yes","sim")

        # Chaves por provedor
        self.google_key     = _env("AGENTS_LLM_GOOGLE_KEY") or _env("GEMINI_API_KEY")
        self.anthropic_key  = _env("AGENTS_LLM_ANTHROPIC_KEY")
        self.openai_key     = _env("AGENTS_LLM_OPENAI_KEY")
        self.minimax_key    = _env("AGENTS_LLM_MINIMAX_KEY")
        self.minimax_group  = _env("AGENTS_LLM_MINIMAX_GROUP_ID")
        self.compat_url     = _env("AGENTS_LLM_COMPAT_URL", "http://localhost:11434/v1")
        self.compat_key     = _env("AGENTS_LLM_COMPAT_KEY", "ollama")

    def is_ready(self) -> tuple[bool, str]:
        """Retorna (ok, motivo) — se a IA está pronta para uso."""
        if not self.enabled:
            return False, "IA desabilitada (AGENTS_LLM_ENABLED=false)"
        keys = {
            "google":       self.google_key,
            "anthropic":    self.anthropic_key,
            "openai":       self.openai_key,
            "minimax":      self.minimax_key,
            "openai_compat": True,  # compat não exige key
        }
        if self.provider not in keys:
            return False, f"Provedor '{self.provider}' não suportado"
        if not keys[self.provider]:
            return False, f"API key não configurada para '{self.provider}'"
        return True, "ok"

    def summary(self) -> dict:
        """Retorno para a API de status — sem expor as keys."""
        ok, reason = self.is_ready()
        return {
            "enabled":  self.enabled,
            "provider": self.provider,
            "model":    self.model,
            "ready":    ok,
            "reason":   reason,
        }

# Instância global (lida uma vez na inicialização do container)
cfg = LLMConfig()

# ─── Chamadas por provedor ────────────────────────────────────────────────────

async def _call_google(prompt: str) -> str:
    url = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        f"{cfg.model}:generateContent?key={cfg.google_key}"
    )
    body = {"contents": [{"parts": [{"text": prompt}]}]}
    async with aiohttp.ClientSession() as s:
        async with s.post(url, json=body,
                          timeout=aiohttp.ClientTimeout(total=30)) as r:
            if r.status == 429:
                raise RuntimeError("Limite de gastos atingido (429). Verifique aistudio.google.com/spend")
            r.raise_for_status()
            data = await r.json()
            return data["candidates"][0]["content"]["parts"][0]["text"]

async def _call_anthropic(prompt: str) -> str:
    url  = "https://api.anthropic.com/v1/messages"
    hdrs = {
        "x-api-key":         cfg.anthropic_key,
        "anthropic-version": "2023-06-01",
        "content-type":      "application/json",
    }
    body = {
        "model":      cfg.model,
        "max_tokens": 1024,
        "messages":   [{"role": "user", "content": prompt}],
    }
    async with aiohttp.ClientSession() as s:
        async with s.post(url, headers=hdrs, json=body,
                          timeout=aiohttp.ClientTimeout(total=30)) as r:
            r.raise_for_status()
            data = await r.json()
            return data["content"][0]["text"]

async def _call_openai(prompt: str, base_url: str = "https://api.openai.com/v1",
                       api_key: Optional[str] = None) -> str:
    url  = f"{base_url.rstrip('/')}/chat/completions"
    key  = api_key or cfg.openai_key
    hdrs = {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
    body = {
        "model":    cfg.model,
        "messages": [{"role": "user", "content": prompt}],
    }
    async with aiohttp.ClientSession() as s:
        async with s.post(url, headers=hdrs, json=body,
                          timeout=aiohttp.ClientTimeout(total=30)) as r:
            r.raise_for_status()
            data = await r.json()
            return data["choices"][0]["message"]["content"]

async def _call_minimax(prompt: str) -> str:
    url  = f"https://api.minimax.chat/v1/text/chatcompletion_v2?GroupId={cfg.minimax_group}"
    hdrs = {"Authorization": f"Bearer {cfg.minimax_key}", "Content-Type": "application/json"}
    body = {
        "model":    cfg.model or "abab6.5s-chat",
        "messages": [{"role": "user", "content": prompt}],
    }
    async with aiohttp.ClientSession() as s:
        async with s.post(url, headers=hdrs, json=body,
                          timeout=aiohttp.ClientTimeout(total=30)) as r:
            r.raise_for_status()
            data = await r.json()
            return data["choices"][0]["message"]["content"]

# ─── Ponto de entrada principal ───────────────────────────────────────────────

async def ask(skill: str, problem: str, memory_ctx: str, check_output: str) -> str:
    """
    Ponto de entrada único para o fallback de IA.

    Monta o prompt padronizado e despacha para o provedor configurado.
    Retorna string vazia (sem exceção) se IA estiver desabilitada ou com erro,
    para não interromper o fluxo de execução do agente.
    """
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
            return await _call_openai(prompt,
                base_url=cfg.compat_url, api_key=cfg.compat_key or None)
        else:
            return f"[Provedor '{cfg.provider}' não suportado]"
    except Exception as e:
        return f"[Erro ao consultar IA ({cfg.provider}): {e}]"

def is_enabled() -> bool:
    """Atalho para verificar se a IA está habilitada — usado nos executors."""
    ok, _ = cfg.is_ready()
    return ok
