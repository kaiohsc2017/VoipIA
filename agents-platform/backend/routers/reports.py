"""routers/reports.py — relatório de execução e alertas"""
from fastapi import APIRouter
from fastapi.responses import HTMLResponse
from uuid import UUID
from database import DB

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

    # Extrai erros com fix_hint do report_json
    failures = []
    report   = exec_data.get("report_json") or {}
    for srv in report.get("servers", []) + [{"checks": report.get("urls", [])}]:
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

    ex      = data["execution"]
    started = str(ex.get("started_at", ""))[:19].replace("T", " ")
    dur     = f"{ex.get('duration_s', 0):.1f}s"
    status_color = {"success": "#16a34a", "error": "#dc2626", "partial": "#d97706"}.get(ex.get("status",""), "#666")

    rows_fail = ""
    for f in data["failures"]:
        fix = f["fix_hint"] or "—"
        rows_fail += f"<tr><td>{f['server']}</td><td>{f['name']}</td><td>{f['reason']}</td><td style='color:#16a34a'>{fix}</td></tr>"

    rows_ai = ""
    for s in data["ai_suggestions"]:
        rows_ai += f"<tr><td>{s['server']}</td><td style='white-space:pre-wrap'>{s['message']}</td></tr>"

    rows_log = ""
    colors = {"success":"#16a34a","error":"#dc2626","warning":"#d97706","info":"#2563eb"}
    for l in data["logs"][-100:]:
        c   = colors.get(l["level"], "#666")
        ts  = str(l.get("ts",""))[:19].replace("T"," ")
        srv = l.get("server") or ""
        rows_log += f"<tr><td style='color:#888;font-size:11px'>{ts}</td><td style='color:{c};font-weight:500'>{l['level']}</td><td style='color:#555'>{srv}</td><td>{l['message']}</td></tr>"

    html = f"""<!DOCTYPE html><html lang="pt-BR"><head>
<meta charset="UTF-8">
<title>Relatório — {ex.get('agent_name','')}</title>
<style>
  body{{font-family:Inter,sans-serif;background:#f0f4f8;color:#1a2340;margin:0;padding:24px}}
  .card{{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:20px 24px;margin-bottom:16px}}
  h1{{font-size:20px;font-weight:700;margin:0 0 4px}}
  h2{{font-size:14px;font-weight:600;margin:0 0 12px;color:#4a5568}}
  .meta{{display:flex;gap:24px;font-size:13px;color:#718096;margin-bottom:20px}}
  .badge{{display:inline-block;padding:3px 10px;border-radius:99px;font-size:12px;font-weight:600;color:#fff;background:{status_color}}}
  table{{width:100%;border-collapse:collapse;font-size:13px}}
  th{{text-align:left;font-size:11px;color:#718096;text-transform:uppercase;letter-spacing:.05em;padding:8px 12px;border-bottom:1px solid #e2e8f0}}
  td{{padding:10px 12px;border-bottom:1px solid #f1f5f9;vertical-align:top}}
  tr:last-child td{{border-bottom:none}}
  .ok{{color:#16a34a;font-weight:600}} .err{{color:#dc2626;font-weight:600}}
  @media print{{body{{padding:0}} .card{{break-inside:avoid}}}}
</style></head><body>
<div class="card">
  <h1>{ex.get('agent_name','')} <span class="badge">{ex.get('status','').upper()}</span></h1>
  <div class="meta">
    <span>Início: {started}</span>
    <span>Duração: {dur}</span>
    <span class="ok">✓ {ex.get('passed_checks',0)} OK</span>
    <span class="err">✗ {ex.get('failed_checks',0)} falhas</span>
    <span>Total: {ex.get('total_checks',0)}</span>
  </div>
  <p style="font-size:13px;color:#4a5568">{ex.get('summary','')}</p>
</div>

{"" if not data["failures"] else f'''
<div class="card">
  <h2>❌ Falhas e como corrigir</h2>
  <table><thead><tr><th>Servidor</th><th>Verificação</th><th>Problema</th><th>Como corrigir</th></tr></thead>
  <tbody>{rows_fail}</tbody></table>
</div>'''}

{"" if not data["ai_suggestions"] else f'''
<div class="card">
  <h2>💡 Sugestões da IA</h2>
  <table><thead><tr><th>Servidor</th><th>Sugestão</th></tr></thead>
  <tbody>{rows_ai}</tbody></table>
</div>'''}

<div class="card">
  <h2>📋 Log de execução</h2>
  <table><thead><tr><th>Horário</th><th>Nível</th><th>Servidor</th><th>Mensagem</th></tr></thead>
  <tbody>{rows_log}</tbody></table>
</div>

<p style="font-size:11px;color:#aaa;text-align:center;margin-top:8px">
  AsteriskIA Agentes · Gerado em {started}
</p>
</body></html>"""
    return HTMLResponse(html)

@router.get("/alerts")
async def recent_alerts(limit: int = 50):
    async with DB() as db:
        rows = await db.fetch("""
            SELECT al.*, a.name as agent_name
            FROM alerts al JOIN agents a ON a.id=al.agent_id
            ORDER BY al.sent_at DESC LIMIT $1
        """, limit)
        return [dict(r) for r in rows]

@router.get("/alerts/unread-count")
async def unread_count():
    async with DB() as db:
        count = await db.fetchval(
            "SELECT COUNT(*) FROM alerts WHERE delivered=true AND sent_at>NOW()-INTERVAL '24h'")
        return {"count": count}
