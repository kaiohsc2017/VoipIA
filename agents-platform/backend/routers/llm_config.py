"""routers/llm_config.py — leitura, escrita e teste da configuração LLM"""
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from typing import Optional
from llm import cfg, ask, is_enabled, read_env_file, write_env_file, reload_config, PROVIDERS_CATALOG, ENV_KEYS
from auth import require_permission

router = APIRouter()

# Config e teste tocam API keys de provedores LLM — leitura e escrita exigem
# PERM_READ/WRITE_agents.llm (ou ADMIN legado). /status e /providers
# continuam públicos (ver _PUBLIC em main.py).
_READ  = [Depends(require_permission("agents.llm", "read"))]
_WRITE = [Depends(require_permission("agents.llm", "write"))]

class LLMSaveRequest(BaseModel):
    AGENTS_LLM_ENABLED:         str = "false"
    AGENTS_LLM_PROVIDER:        str = "google"
    AGENTS_LLM_MODEL:           str = "gemini-2.5-flash"
    AGENTS_LLM_GOOGLE_KEY:      Optional[str] = ""
    AGENTS_LLM_ANTHROPIC_KEY:   Optional[str] = ""
    AGENTS_LLM_OPENAI_KEY:      Optional[str] = ""
    AGENTS_LLM_MINIMAX_KEY:     Optional[str] = ""
    AGENTS_LLM_MINIMAX_GROUP_ID:Optional[str] = ""
    AGENTS_LLM_COMPAT_URL:      Optional[str] = "http://localhost:11434/v1"
    AGENTS_LLM_COMPAT_KEY:      Optional[str] = ""

@router.get("/status")
async def llm_status():
    """Status atual — sem expor as API keys."""
    return cfg.summary()

@router.get("/config", dependencies=_READ)
async def llm_config_read():
    """Lê o .env.agents atual — retorna valores mascarando as keys."""
    values = read_env_file()
    # Mascara as keys para não expor no frontend
    masked = {}
    for k, v in values.items():
        if "KEY" in k and v:
            masked[k] = v[:8] + "••••••••" + v[-4:] if len(v) > 12 else "••••••••"
        else:
            masked[k] = v
    return {"values": masked, "has_file": True}

@router.get("/config/full", dependencies=_WRITE)
async def llm_config_read_full():
    """Lê o .env.agents com as keys em texto puro — usado só pelo formulário de
    edição (mascarar aqui faria o usuário sobrescrever uma key válida com a
    string mascarada ao salvar outro campo). Gate por permissão de escrita,
    não de leitura, por expor os segredos sem máscara."""
    return {"values": read_env_file(), "has_file": True}

@router.post("/config", dependencies=_WRITE)
async def llm_config_save(body: LLMSaveRequest):
    """Salva o .env.agents e recarrega a configuração em memória."""
    values = {k: (getattr(body, k) or "") for k in ENV_KEYS}
    try:
        write_env_file(values)
        reload_config()
        return {"ok": True, "status": cfg.summary()}
    except Exception as e:
        raise HTTPException(500, f"Erro ao salvar configuração: {e}")

@router.get("/providers")
async def llm_providers():
    """Lista provedores suportados com modelos populares."""
    return {"providers": PROVIDERS_CATALOG, "current": cfg.summary()}

@router.post("/test", dependencies=_WRITE)
async def llm_test():
    """Testa o LLM ativo com prompt simples."""
    if not is_enabled():
        s = cfg.summary()
        return {"ok": False, "error": s["reason"] or "IA desabilitada"}
    try:
        response = await ask(
            skill="Especialista em infraestrutura Linux",
            problem="Teste de conectividade do painel VoipIA Agentes",
            memory_ctx="",
            check_output="echo 'teste' → ok",
        )
        # Verifica se a resposta é uma mensagem de erro do próprio módulo
        if response.startswith("["):
            return {"ok": False, "error": response}
        return {
            "ok": True,
            "provider": cfg.provider,
            "model":    cfg.model,
            "response": response[:400],
        }
    except Exception as e:
        return {"ok": False, "error": str(e)}
