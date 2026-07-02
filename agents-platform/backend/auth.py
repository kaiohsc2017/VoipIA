"""auth.py — RBAC do FastAPI de Agentes.

O JWT é emitido pelo backend Java (AuthController) e compartilha o mesmo
BACKEND_JWT_SECRET — a claim "role" (ADMIN|USER, default USER) já vem
preenchida desde a implementação do RBAC no Telecom. Aqui só extraímos
(main.py, jwt_middleware) e aplicamos como dependency nas rotas sensíveis.
"""
from fastapi import Request, HTTPException


def require_admin(request: Request) -> None:
    """Dependency FastAPI: bloqueia com 403 se o usuário autenticado não for ADMIN."""
    if getattr(request.state, "role", "USER") != "ADMIN":
        raise HTTPException(403, "Acesso restrito a administradores")
