"""routers/llm_config.py — endpoints de configuração e status do LLM"""
from fastapi import APIRouter
from llm import cfg, ask, is_enabled

router = APIRouter()

@router.get("/status")
async def llm_status():
    """Retorna estado atual do LLM sem expor as API keys."""
    return cfg.summary()

@router.get("/providers")
async def llm_providers():
    """Lista provedores suportados com os modelos populares de cada um."""
    return {
        "providers": [
            {
                "id": "google",
                "name": "Google Gemini",
                "env_key": "AGENTS_LLM_GOOGLE_KEY",
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
                "env_key": "AGENTS_LLM_ANTHROPIC_KEY",
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
                "env_key": "AGENTS_LLM_OPENAI_KEY",
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
                "env_key": "AGENTS_LLM_MINIMAX_KEY",
                "models": [
                    "abab6.5s-chat",
                    "abab6.5g-chat",
                    "abab5.5s-chat",
                ],
                "docs": "https://www.minimax.chat",
            },
            {
                "id": "openai_compat",
                "name": "API Compatível OpenAI (Ollama / Groq / Together)",
                "env_key": "AGENTS_LLM_COMPAT_URL",
                "models": [
                    "llama3.2",
                    "mistral",
                    "llama-3.3-70b-versatile",
                ],
                "docs": "https://github.com/ollama/ollama",
            },
        ],
        "current": cfg.summary(),
    }

@router.post("/test")
async def llm_test():
    """Testa o LLM atual com um prompt simples."""
    if not is_enabled():
        return {"ok": False, "error": cfg.summary()["reason"]}
    try:
        response = await ask(
            skill="Especialista em infraestrutura Linux",
            problem="Teste de conectividade",
            memory_ctx="",
            check_output="ping -c1 8.8.8.8 → OK",
        )
        return {"ok": True, "provider": cfg.provider, "model": cfg.model,
                "response": response[:300]}
    except Exception as e:
        return {"ok": False, "error": str(e)}
