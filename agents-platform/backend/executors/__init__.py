"""
executors/ — um executor especializado por tipo de agente (fase 23, O3.4 da
refatoração, extraído de executor.py). Reexporta EXECUTORS (dispatcher usado
por orchestrator.py) e _build_ssh_kwargs (usado por routers/servers.py).
"""
from executors.common import _build_ssh_kwargs
from executors.database_executor import DatabaseExecutor
from executors.log_executor import LogMonitorExecutor
from executors.ssh_executor import SSHTestExecutor
from executors.web_executor import WebMonitorExecutor

EXECUTORS = {
    "ssh_test":    SSHTestExecutor,
    "web_monitor": WebMonitorExecutor,
    "log_monitor": LogMonitorExecutor,
    "database":    DatabaseExecutor,
}
