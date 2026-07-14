"""routers/reports.py — relatório de execução e alertas"""
import json
from fastapi import APIRouter, Query
from fastapi.responses import HTMLResponse
from uuid import UUID
from database import DB, fetch_recent_alerts
from routers.report_html import render_execution_report_html

router = APIRouter()
@router.get("/execution/{execution_id}")
async def execution_report(execution_id: UUID):
    """Relatório completo de uma execução — para tela de detalhe."""
    async with DB() as db:
        exec_row = await db.fetchrow("""
            SELECT e.*, a.name as agent_name, a.type as agent_type, a.skill
            FROM executions e JOIN agents a ON a.id=e.agent_id
            WHERE e.id=$1
        """, execution_id)
        if not exec_row:
            return {"error": "execução não encontrada"}

        logs = await db.fetch("""
            SELECT level, ts, server, message, raw_output
            FROM execution_logs WHERE execution_id=$1 ORDER BY ts ASC
        """, execution_id)

    exec_data = dict(exec_row)
    log_list  = [dict(r) for r in logs]

    # Extrai sugestões de IA dos logs
    ai_suggestions = [
        {"ts": l["ts"], "server": l["server"], "message": l["message"][len("💡 IA sugere: "):]}
        for l in log_list if l["message"].startswith("💡 IA sugere:")
    ]

    # Extrai erros com fix_hint do report_json — 3 formatos possíveis:
    # SSHTestExecutor/LogMonitorExecutor (report["servers"][].checks),
    # WebMonitorExecutor (report["urls"]) e DatabaseExecutor (report["checks"]
    # na raiz, sem noção de "servidor" — ver executor.py:648-705).
    failures = []
    report = exec_data.get("report_json") or {}
    # JSONB via asyncpg sem codec chega como string crua — achado emergente
    # durante o teste de O1.4: sem isso, este endpoint 500 para qualquer execução.
    if isinstance(report, str):
        report = json.loads(report) if report else {}
    groups = report.get("servers", []) + [{"checks": report.get("urls", [])}] \
        + [{"checks": report.get("checks", [])}]
    for srv in groups:
        for chk in srv.get("checks", []):
            if not chk.get("ok"):
                failures.append({
                    "name":     chk.get("name", chk.get("url", "")),
                    "server":   srv.get("server", chk.get("url", "")),
                    "reason":   chk.get("reason", ""),
                    "fix_hint": chk.get("fix_hint", ""),
                })

    return {
        "execution":     exec_data,
        "logs":          log_list,
        "failures":      failures,
        "ai_suggestions": ai_suggestions,
        "summary":        exec_data.get("summary", ""),
    }

@router.get("/execution/{execution_id}/html", response_class=HTMLResponse)
async def execution_report_html(execution_id: UUID):
    """Relatório HTML exportável de uma execução."""
    data = await execution_report(execution_id)
    if "error" in data:
        return HTMLResponse("<p>Execução não encontrada.</p>", status_code=404)
    return HTMLResponse(render_execution_report_html(data))

@router.get("/alerts")
async def recent_alerts(limit: int = Query(default=50, le=500)):
    return await fetch_recent_alerts(limit)

@router.get("/alerts/unread-count")
async def unread_count():
    async with DB() as db:
        count = await db.fetchval(
            "SELECT COUNT(*) FROM alerts WHERE delivered=true AND sent_at>NOW()-INTERVAL '24h'")
        return {"count": count}
