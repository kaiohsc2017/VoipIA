"""notifier.py — envio de alertas: Telegram, Web, E-mail, Webhook"""
import asyncio, aiohttp, os, json, smtplib, ssl, logging
from email.mime.text import MIMEText
from datetime import datetime, timezone

logger = logging.getLogger("asteriskia.notifier")

TELEGRAM_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
SMTP_HOST      = os.environ.get("AGENTS_SMTP_HOST", "")
SMTP_PORT      = int(os.environ.get("AGENTS_SMTP_PORT", "587"))
SMTP_USER      = os.environ.get("AGENTS_SMTP_USER", "")
SMTP_PASS      = os.environ.get("AGENTS_SMTP_PASS", "")
SMTP_FROM      = os.environ.get("AGENTS_SMTP_FROM", SMTP_USER)

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
    except Exception as e:
        logger.error("[notifier] telegram error: %s", e)
        return False


def _send_email_sync(to: str, subject: str, body: str) -> bool:
    """Envio SMTP síncrono — deve ser chamado apenas via asyncio.to_thread."""
    msg = MIMEText(body, "html", "utf-8")
    msg["Subject"] = subject
    msg["From"]    = SMTP_FROM
    msg["To"]      = to
    ctx = ssl.create_default_context()
    with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15) as srv:
        srv.ehlo()
        srv.starttls(context=ctx)
        srv.login(SMTP_USER, SMTP_PASS)
        srv.sendmail(SMTP_FROM, [to], msg.as_string())
    return True


async def send_email(to: str, subject: str, body: str) -> bool:
    """Envia e-mail via SMTP sem bloquear o event loop."""
    if not SMTP_HOST or not SMTP_USER or not to:
        return False
    try:
        return await asyncio.to_thread(_send_email_sync, to, subject, body)
    except Exception as e:
        logger.error("[notifier] e-mail error: %s", e)
        return False

async def send_webhook(url: str, payload: dict) -> bool:
    """Envia POST JSON para webhook configurado no agente."""
    if not url:
        return False
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                url, json=payload,
                headers={"Content-Type": "application/json", "User-Agent": "AsteriskIA-Agents/2.0"},
                timeout=aiohttp.ClientTimeout(total=15)
            ) as r:
                return r.status < 400
    except Exception as e:
        logger.error("[notifier] webhook error: %s", e)
        return False

async def send_web_alert(agent_id: str, level: str, message: str):
    """Mantido para compatibilidade — broadcast feito diretamente no executor."""
    pass
