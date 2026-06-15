"""notifier.py — envio de alertas Telegram e web"""
import aiohttp, os, json
from datetime import datetime, timezone

TELEGRAM_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")

async def send_telegram(chat_id: str, message: str) -> bool:
    if not TELEGRAM_TOKEN:
        return False
    url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage"
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(url, json={
                "chat_id": chat_id,
                "text": message,
                "parse_mode": "HTML",
            }, timeout=aiohttp.ClientTimeout(total=10)) as r:
                return r.status == 200
    except Exception:
        return False

async def send_web_alert(agent_id: str, level: str, message: str):
    """
    Alertas web são gravados no banco pelo executor e transmitidos via
    broadcast WebSocket para o canal __alerts__ — lidos pela UI em tempo real.
    Esta função é mantida para compatibilidade mas a gravação acontece no executor.
    """
    pass
