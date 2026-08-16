"""notifier.py — envio de alertas: Telegram, Web, E-mail, Webhook"""
import asyncio, aiohttp, os, json, smtplib, ssl, logging, socket, ipaddress
from email.mime.text import MIMEText
from urllib.parse import urlparse
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

async def _is_safe_public_url(url: str) -> bool:
    """Achado de segurança (SSRF): notify_webhook_url é campo livre, editável
    por qualquer usuário com PERM_WRITE_agents.agents — sem esta checagem,
    alguém aponta pra 172.16.7.11:5432 ou 169.254.169.254 e força o
    container a fazer a requisição. Resolve o host e bloqueia qualquer IP
    privado/loopback/link-local antes de disparar o POST."""
    try:
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            return False
        infos = await asyncio.to_thread(socket.getaddrinfo, parsed.hostname, None)
        for family, _, _, _, sockaddr in infos:
            ip = ipaddress.ip_address(sockaddr[0])
            if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
                return False
        return True
    except Exception:
        return False


async def send_webhook(url: str, payload: dict) -> bool:
    """Envia POST JSON para webhook configurado no agente."""
    if not url:
        return False
    if not await _is_safe_public_url(url):
        logger.warning("[notifier] webhook bloqueado — host privado/loopback/inválido: %s", url)
        return False
    try:
        async with aiohttp.ClientSession() as session:
            # allow_redirects=False: um host público controlado pelo atacante
            # responderia 302 pra um IP privado sem passar de novo por
            # _is_safe_public_url — webhook de notificação não precisa seguir redirect.
            async with session.post(
                url, json=payload,
                headers={"Content-Type": "application/json", "User-Agent": "VoipIA-Agents/2.0"},
                timeout=aiohttp.ClientTimeout(total=15),
                allow_redirects=False
            ) as r:
                return r.status < 400
    except Exception as e:
        logger.error("[notifier] webhook error: %s", e)
        return False

async def send_web_alert(agent_id: str, level: str, message: str):
    """Mantido para compatibilidade — broadcast feito diretamente no executor."""
    pass
