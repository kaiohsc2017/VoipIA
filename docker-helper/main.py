"""
docker-helper/main.py — único ponto de acesso ao /var/run/docker.sock no stack.

Antes (F-CRIT-10), o backend Java montava o docker.sock diretamente e rodava
`docker compose`/`docker logs`/`docker exec` via ProcessBuilder — qualquer RCE
no container do backend virava root no host. Este serviço concentra esse
acesso: só ele monta o socket, não publica porta no host (só alcançável pela
rede interna voipia-net) e exige o header X-Internal-Key (mesmo
INTERNAL_API_KEY já usado entre backend e ai-agent).

Não expõe exec genérico — cada endpoint permite exatamente a operação que os
controllers Java já faziam antes (nada de "rode este comando arbitrário").
"""
import asyncio
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

app = FastAPI(title="VoipIA Docker Helper", version="1.0.0")

_INTERNAL_KEY = os.environ["INTERNAL_API_KEY"]
_COMPOSE_DIR  = "/opt/VoipIA"
_ENV_FILE     = "/opt/VoipIA/env/.env"
_ASTERISK_CONTAINER = "voipia-asterisk"
_ASTERISK_LOG_FILE  = "/var/log/asterisk/full"

# Allowlist de containers do stack — nunca repassa o valor do cliente direto
# ao subprocess sem checar contra esta lista.
_ALLOWED_SERVICES = {
    "voipia-backend", "voipia-asterisk", "voipia-ai-agent",
    "voipia-frontend", "voipia-postgres", "voipia-agents-api",
    "voipia-caddy", "voipia-security",
}

# Allowlist das chaves de serviço do docker-compose.yml (nomes usados em
# `docker compose up <service>`, diferentes dos nomes de container acima).
# Achado de segurança: /compose/up repassava `services` do body direto pro
# subprocess sem checar contra nenhuma allowlist — único container com acesso
# ao docker.sock, então deveria validar por conta própria mesmo que o backend
# Java já valide antes.
_ALLOWED_COMPOSE_SERVICES = {
    "postgres", "asterisk", "ai-agent", "docker-helper", "backend",
    "frontend", "coturn", "security", "agents-backend", "caddy",
}


async def check_internal_key(x_internal_key: str = Header(default="")):
    if x_internal_key != _INTERNAL_KEY:
        raise HTTPException(401, "Chave interna inválida")


def _resolve_service(name: str) -> str:
    svc = name if name.startswith("asteriskia-") else f"asteriskia-{name}"
    if svc not in _ALLOWED_SERVICES:
        raise HTTPException(400, f"Serviço desconhecido: {svc}")
    return svc


async def _run(*cmd: str, cwd: str | None = None) -> tuple[int, str]:
    proc = await asyncio.create_subprocess_exec(
        *cmd, cwd=cwd,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
    )
    out, _ = await proc.communicate()
    return proc.returncode, out.decode("utf-8", errors="replace")


async def _stream_lines(*cmd: str):
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    try:
        while True:
            line = await proc.stdout.readline()
            if not line:
                break
            yield line
    finally:
        proc.kill()
        await proc.wait()


@app.get("/health")
async def health():
    """Público — usado pelo healthcheck do docker-compose."""
    return {"status": "ok"}


@app.post("/compose/up", dependencies=[Depends(check_internal_key)])
async def compose_up(body: dict):
    """Equivalente ao antigo SettingsService.runApply() via ProcessBuilder."""
    services = [s for s in body.get("services", []) if isinstance(s, str) and s.strip()]
    invalid = [s for s in services if s not in _ALLOWED_COMPOSE_SERVICES]
    if invalid:
        raise HTTPException(400, f"Serviço(s) desconhecido(s): {', '.join(invalid)}")
    cmd = ["docker", "compose", "--env-file", _ENV_FILE]
    if services:
        cmd += ["up", "-d", "--no-deps", *services]
    else:
        cmd += ["up", "-d", "--remove-orphans"]
    exit_code, output = await _run(*cmd, cwd=_COMPOSE_DIR)
    return {"exitCode": exit_code, "output": output}


@app.get("/logs/{service}", dependencies=[Depends(check_internal_key)])
async def get_logs(service: str, tail: int = 200, since: str | None = None, until: str | None = None):
    """Equivalente ao antigo LogsController.runDockerLogs() via ProcessBuilder."""
    svc = _resolve_service(service)
    cmd = ["docker", "logs", "--timestamps", "--tail", str(tail)]
    if since:
        cmd += ["--since", since]
    if until:
        cmd += ["--until", until]
    cmd.append(svc)
    _, output = await _run(*cmd)
    return {"lines": output.splitlines()}


@app.get("/logs/{service}/stream", dependencies=[Depends(check_internal_key)])
async def stream_logs(service: str, tail: int = 50):
    """Equivalente ao antigo LogsController.dockerStream() (docker logs --follow)."""
    svc = _resolve_service(service)
    cmd = ("docker", "logs", "--follow", "--tail", str(tail), "--timestamps", svc)
    return StreamingResponse(_stream_lines(*cmd), media_type="text/plain")


@app.get("/asterisk/log", dependencies=[Depends(check_internal_key)])
async def asterisk_log(lines: int = 2000):
    """Equivalente ao antigo tailAsteriskLog() via `docker exec ... tail -n`."""
    cmd = ("docker", "exec", _ASTERISK_CONTAINER, "tail", "-n", str(lines), _ASTERISK_LOG_FILE)
    _, output = await _run(*cmd)
    return {"lines": output.splitlines()}


@app.get("/asterisk/log/stream", dependencies=[Depends(check_internal_key)])
async def asterisk_log_stream(lines: int = 50):
    """Equivalente ao antigo LogsController.asteriskStream() (`docker exec ... tail -F`)."""
    cmd = ("docker", "exec", _ASTERISK_CONTAINER, "tail", "-F", "-n", str(lines), _ASTERISK_LOG_FILE)
    return StreamingResponse(_stream_lines(*cmd), media_type="text/plain")
