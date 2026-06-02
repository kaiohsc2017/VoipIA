"""
main.py — Scheduler de testes de conectividade (Módulo 2)

Fluxo principal:
  1. A cada SCHEDULER_POLL_INTERVAL_SECS, busca NumberTests ativos no backend
  2. Para cada teste, verifica se está na janela de horário e intervalo
  3. Origina chamadas AMI sequencialmente (quantidade configurada)
  4. Registra resultado de cada chamada no backend
"""

import asyncio
import logging
from datetime import datetime, time as dt_time
from typing import Optional

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from src.config import SCHEDULER_POLL_INTERVAL_SECS
from src.services.ami_service import AmiService
from src.services.backend_service import BackendService

# Configuração de logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("scheduler.main")


class ConnectivityScheduler:
    """
    Gerenciador de testes de conectividade.

    Controla a execução sequencial de chamadas telefônicas de teste,
    respeitando horário de início, intervalo e quantidade configurados
    para cada NumberTest.
    """

    def __init__(self):
        self.ami = AmiService()
        self.backend = BackendService()
        # Rastreamento: {number_test_id: {"count": int, "last_call": datetime}}
        self._test_state: dict[int, dict] = {}

    async def run_cycle(self) -> None:
        """
        Ciclo principal do scheduler.
        Busca testes ativos e executa os que estão na janela correta.
        """
        logger.debug("Iniciando ciclo de verificação...")
        tests = self.backend.get_active_number_tests()

        for test in tests:
            test_id = test["id"]
            try:
                await self._maybe_run_test(test)
            except Exception as e:
                logger.error("Erro ao processar teste id=%d: %s", test_id, e)

    async def _maybe_run_test(self, test: dict) -> None:
        """
        Verifica se o teste deve ser executado agora e, em caso positivo, executa.
        """
        test_id = test["id"]
        phone = test["phoneNumber"]
        quantity = test.get("quantity", 1)
        interval_minutes = test.get("intervalMinutes", 60)
        start_time_str = test.get("startTime", "00:00:00")

        now = datetime.now()
        state = self._test_state.setdefault(test_id, {"count": 0, "last_call": None})

        # Verifica horário de início
        try:
            parts = start_time_str.split(":")
            start_time = dt_time(int(parts[0]), int(parts[1]))
            current_time = now.time()
            if current_time < start_time:
                return  # Antes do horário de início
        except (ValueError, IndexError):
            pass

        # Verifica se atingiu a quantidade de execuções do dia
        if state["count"] >= quantity:
            # Reseta no próximo dia
            if state["last_call"] and state["last_call"].date() < now.date():
                state["count"] = 0
            else:
                return

        # Verifica intervalo entre chamadas
        if state["last_call"]:
            elapsed_minutes = (now - state["last_call"]).total_seconds() / 60
            if elapsed_minutes < interval_minutes:
                return

        # Executa o teste
        execution_order = state["count"] + 1
        logger.info("Executando teste %d para %s (execução %d/%d)",
                    test_id, phone, execution_order, quantity)

        status, sip_code, sip_reason = await self.ami.originate_test_call(
            phone_number=phone,
            test_result_id=test_id,  # Temporário: ID do test usado como referência
            execution_order=execution_order,
        )

        # Registra resultado no backend
        self.backend.register_test_result(
            number_test_id=test_id,
            status=status,
            sip_response_code=sip_code,
            sip_response_reason=sip_reason,
            execution_order=execution_order,
        )

        # Atualiza estado
        state["count"] = execution_order
        state["last_call"] = now

        logger.info("Teste %d concluído: %s (SIP %s)", test_id, status, sip_code or "N/A")


async def main() -> None:
    """Inicializa e mantém o scheduler em execução."""
    logger.info("=== AsteriskIA Scheduler iniciando ===")

    scheduler_service = ConnectivityScheduler()

    # Conecta ao AMI na inicialização
    try:
        await scheduler_service.ami.connect()
    except Exception as e:
        logger.warning("AMI não disponível na inicialização: %s — continuando sem AMI", e)

    # APScheduler para executar o ciclo periodicamente
    scheduler = AsyncIOScheduler()
    scheduler.add_job(
        scheduler_service.run_cycle,
        trigger="interval",
        seconds=SCHEDULER_POLL_INTERVAL_SECS,
        id="connectivity_check",
        name="Verificação de Conectividade",
        replace_existing=True,
    )
    scheduler.start()
    logger.info("Scheduler iniciado — ciclo a cada %ds", SCHEDULER_POLL_INTERVAL_SECS)

    # Mantém o processo vivo
    try:
        while True:
            await asyncio.sleep(3600)
    except (KeyboardInterrupt, SystemExit):
        logger.info("Scheduler encerrado")
        scheduler.shutdown()
        await scheduler_service.ami.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
