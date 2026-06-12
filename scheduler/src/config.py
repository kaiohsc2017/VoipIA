"""
config.py - Configuracoes do Scheduler Python (Modulo 2)

Abordagem 3 - reload dinamico sem restart:
  INTERNAL_API_KEY, AMI_PASSWORD e demais credenciais sao relidos
  do .env em disco a cada ciclo de polling via get_config().
  Alteracoes na tela de Settings refletem sem restart do container.
"""

import os
from dotenv import load_dotenv, dotenv_values
from pathlib import Path

_ENV_PATH = Path(os.getenv("DOTENV_PATH", "/opt/asteriskia/env/.env"))

load_dotenv(_ENV_PATH if _ENV_PATH.exists() else None)

# --- Variaveis estruturais ---

BACKEND_URL: str  = os.getenv("BACKEND_URL", "http://backend:8080")
AMI_HOST: str     = os.getenv("AST_AMI_HOST", "asterisk")
AMI_PORT: int     = int(os.getenv("AST_AMI_PORT", "5038"))
TRUNK_NAME: str   = os.getenv("AMI_TRUNK_NAME", "trunk-saida")
DIALPLAN_TEST_CONTEXT: str = os.getenv("DIALPLAN_TEST_CONTEXT", "asteriskia-test")
SCHEDULER_POLL_INTERVAL_SECS: int = int(os.getenv("SCHEDULER_POLL_INTERVAL_SECS", "30"))
AMI_ORIGINATE_TIMEOUT_MS: int     = int(os.getenv("AMI_ORIGINATE_TIMEOUT_MS", "30000"))


# --- Leitura dinamica ---

def get_config(key: str, default: str = "") -> str:
    if _ENV_PATH.exists():
        val = dotenv_values(_ENV_PATH).get(key, "")
        if val:
            return val
    return os.getenv(key, default)

# Credenciais que podem mudar sem restart
def get_internal_api_key() -> str: return get_config("INTERNAL_API_KEY", "internal_changeme_dev")
def get_ami_user()         -> str: return get_config("AST_AMI_USER", "admin")
def get_ami_password()     -> str: return get_config("AST_AMI_PASSWORD", "")

# Aliases para compatibilidade com imports existentes
INTERNAL_API_KEY: str = os.getenv("INTERNAL_API_KEY", "internal_changeme_dev")
AMI_USER: str         = os.getenv("AST_AMI_USER", "admin")
AMI_PASSWORD: str     = os.getenv("AST_AMI_PASSWORD", "")
