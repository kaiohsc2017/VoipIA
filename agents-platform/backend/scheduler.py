"""scheduler.py — agendador de execuções com suporte a cron"""
import asyncio, json, logging
from datetime import datetime, timezone, timedelta
from database import DB
from executor import run_agent

logger = logging.getLogger("asteriskia.scheduler")

try:
    from croniter import croniter
    HAS_CRONITER = True
except ImportError:
    HAS_CRONITER = False

def _parse_interval(value: str) -> int:
    """Converte '5m', '1h', '30s' em segundos."""
    units = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    try:
        return int(value[:-1]) * units.get(value[-1], 60)
    except Exception:
        return 300

def _next_cron_sleep(expression: str) -> float:
    """Retorna segundos até próxima execução da expressão cron."""
    if not HAS_CRONITER:
        return 300.0
    try:
        now  = datetime.now(timezone.utc)
        cron = croniter(expression, now)
        nxt  = cron.get_next(datetime)
        return max(0.0, (nxt - now).total_seconds())
    except Exception:
        return 300.0

class AgentScheduler:
    def __init__(self):
        self._tasks: dict[str, asyncio.Task] = {}
        self._running = False
        self._broadcast = None

    def set_broadcast(self, fn):
        self._broadcast = fn

    async def start(self):
        self._running = True
        asyncio.create_task(self._loader())

    async def stop(self):
        self._running = False
        for t in self._tasks.values():
            t.cancel()
        # Aguarda o término real das tasks canceladas — garante que conexões
        # SSH/DB abertas dentro de run_agent fechem limpo antes do shutdown.
        await asyncio.gather(*self._tasks.values(), return_exceptions=True)

    async def _loader(self):
        await asyncio.sleep(3)
        async with DB() as db:
            rows = await db.fetch("SELECT * FROM agents WHERE status != 'paused'")
            for row in rows:
                self._schedule_agent(dict(row))

    def _schedule_agent(self, agent: dict):
        aid = str(agent["id"])
        if aid in self._tasks:
            self._tasks[aid].cancel()
        self._tasks[aid] = asyncio.create_task(self._run_loop(agent))

    async def _run_loop(self, agent: dict):
        schedule = agent.get("schedule") or {}
        if isinstance(schedule, str):
            schedule = json.loads(schedule)

        stype  = schedule.get("type", "interval")
        value  = schedule.get("value", "5m")
        active = schedule.get("active", True)

        if not active:
            return

        while self._running:
            try:
                await run_agent(agent, self._broadcast or self._noop_broadcast)
            except Exception as e:
                logger.error("[scheduler] Erro agente %s: %s", agent["name"], e)

            if stype == "once":
                break
            elif stype == "always":
                await asyncio.sleep(10)
            elif stype == "cron":
                # Suporte a expressões cron: "0 2 * * *", "*/5 * * * *" etc.
                sleep_s = _next_cron_sleep(value)
                if not self._running:
                    break
                await asyncio.sleep(sleep_s)
            else:
                # interval: "5m", "1h", "30s"
                await asyncio.sleep(_parse_interval(value))

    async def run_now(self, agent_id: str) -> dict:
        async with DB() as db:
            row = await db.fetchrow("SELECT * FROM agents WHERE id=$1::uuid", agent_id)
            if not row:
                return {"error": "agente não encontrado"}
        return await run_agent(dict(row), self._broadcast or self._noop_broadcast)

    def reload_agent(self, agent: dict):
        """Recarrega o agendamento de um agente (após edição)."""
        self._schedule_agent(agent)

    async def _noop_broadcast(self, *_):
        pass
