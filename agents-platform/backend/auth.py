"""auth.py — RBAC do FastAPI de Agentes.

O JWT é emitido pelo backend Java (AuthController) e compartilha o mesmo
BACKEND_JWT_SECRET. Duas claims chegam prontas (main.py, jwt_middleware):
- "role" (ADMIN|USER, default USER) — legado, RBAC binário original.
- "perm" ({resource_key: "r"|"w"|"rw"}) — grupos de acesso granulares (V22),
  resolvida do grupo do usuário no login/refresh pelo lado Java. Tokens
  emitidos antes deste deploy não têm essa claim (dict vazio).
"""
from fastapi import Request, HTTPException


def require_admin(request: Request) -> None:
    """Dependency FastAPI: bloqueia com 403 se o usuário autenticado não for ADMIN.

    Usada só para operações sem menu próprio no catálogo de recursos (ex:
    retenção em system.py) — não faz sentido checar uma permissão granular
    por recurso quando não existe um recurso (página) correspondente.
    """
    if getattr(request.state, "role", "USER") != "ADMIN":
        raise HTTPException(403, "Acesso restrito a administradores")


def require_permission(resource_key: str, action: str):
    """Dependency factory FastAPI: bloqueia com 403 se o usuário não tiver a
    permissão `action` ("read"|"write") no `resource_key`.

    ADMIN (claim "role" legada) sempre passa — isso é o que permite tokens
    emitidos antes deste deploy (sem a claim "perm") continuarem válidos até
    expirar/renovar, espelhando o dual-emit equivalente no SecurityConfig.java.
    """
    flag = "r" if action == "read" else "w"

    def _check(request: Request) -> None:
        if getattr(request.state, "role", "USER") == "ADMIN":
            return
        perms: dict = getattr(request.state, "perms", {})
        if flag not in perms.get(resource_key, ""):
            raise HTTPException(403, "Acesso negado a este recurso")

    return _check
