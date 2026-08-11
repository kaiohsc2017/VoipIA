"""
executor.py — shim de compatibilidade (fase 23, O3.4 da refatoração).

A lógica real de execução dos agentes foi movida para:
  - executors/ — um executor especializado por tipo de agente (SSHTestExecutor,
    WebMonitorExecutor, LogMonitorExecutor, DatabaseExecutor) + common.py
  - orchestrator.py — run_agent (cria execução, despacha pro executor certo,
    agrega resultado, dispara alertas/auto-fix/encadeamento, aplica retenção)

Mantido para não quebrar os pontos que ainda importam daqui: scheduler.py
(run_agent), routers/servers.py (_build_ssh_kwargs), routers/system.py
(_apply_retention).
"""
from executors import _build_ssh_kwargs  # noqa: F401
from orchestrator import _apply_retention, run_agent  # noqa: F401
