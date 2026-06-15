"""scheduler.py — agendador de execuções"""
import asyncio, json
from datetime import datetime, timezone, timedelta
from database import DB
from executor import run_agent

def _parse_interval(value: str) -> int:
    """Converte '5m', '1h', '30s' em segundos."""
    units = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    try:
        return int(value[:-1]) * units.get(value[-1], 60)
    except Exception:
        return 300

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

    async def _loader(self):
        """Carrega agentes ativos e inicia tasks de agendamento."""
        await asyncio.sleep(3)  # aguarda DB
        async with DB() as db:
            rows = await db.fetch("SELECT * FROM agents WHERE status != 'paused'")
            for row in rows:
                agent = dict(row)
                self._schedule_agent(agent)

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

        interval = _parse_interval(value) if stype == "interval" else 300

        while self._running:
            try:
                await run_agent(agent, self._broadcast or self._noop_broadcast)
            except Exception as e:
                print(f"[scheduler] Erro no agente {agent['name']}: {e}")

            if stype == "once":
                break
            if stype == "always":
                await asyncio.sleep(10)
            else:
                await asyncio.sleep(interval)

    async def run_now(self, agent_id: str) -> dict:
        async with DB() as db:
            row = await db.fetchrow("SELECT * FROM agents WHERE id=$1::uuid", agent_id)
            if not row:
                return {"error": "agente não encontrado"}
            agent = dict(row)
        return await run_agent(agent, self._broadcast or self._noop_broadcast)

    async def _noop_broadcast(self, *_):
        pass
