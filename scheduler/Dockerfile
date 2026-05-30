# =============================================================
# Dockerfile — Scheduler Python (Módulo 2 + Módulo 3)
# Motor de agendamento para testes de conectividade e
# polling do Zabbix.
# =============================================================

FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

CMD ["python", "-m", "src.main"]
