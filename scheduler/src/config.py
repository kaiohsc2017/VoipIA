"""
config.py — Configurações do Scheduler Python (Módulo 2)
Todas as configurações via variáveis de ambiente.
"""

import os
from dotenv import load_dotenv

load_dotenv()

# Backend REST API
BACKEND_URL = os.getenv("BACKEND_URL", "http://backend:8080")
# Chave compartilhada para auth interna — deve ser igual ao INTERNAL_API_KEY do backend
INTERNAL_API_KEY = os.getenv("INTERNAL_API_KEY", "internal_changeme_dev")

# Asterisk AMI
AMI_HOST = os.getenv("AST_AMI_HOST", "asterisk")
AMI_PORT = int(os.getenv("AST_AMI_PORT", "5038"))
AMI_USER = os.getenv("AST_AMI_USER", "admin")
AMI_PASSWORD = os.getenv("AST_AMI_PASSWORD", "")

# Configurações do scheduler
SCHEDULER_POLL_INTERVAL_SECS = int(os.getenv("SCHEDULER_POLL_INTERVAL_SECS", "30"))
AMI_ORIGINATE_TIMEOUT_MS = int(os.getenv("AMI_ORIGINATE_TIMEOUT_MS", "30000"))

# Tronco de saída configurado no PJSIP
TRUNK_NAME = os.getenv("AMI_TRUNK_NAME", "trunk-saida")

# Contexto do dialplan para testes de conectividade
DIALPLAN_TEST_CONTEXT = os.getenv("DIALPLAN_TEST_CONTEXT", "asteriskia-test")
