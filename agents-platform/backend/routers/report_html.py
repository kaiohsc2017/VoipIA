"""routers/report_html.py — template do relatório HTML exportável de execução.

Extraído de reports.py (O4.5 do roadmap de refatoração) — mesma lógica, mesmo HTML gerado.
"""
from html import escape as _esc


def render_execution_report_html(data: dict) -> str:
    ex = data["execution"]
    started = str(ex.get("started_at", ""))[:19].replace("T", " ")
    dur = f"{ex.get('duration_s', 0):.1f}s"
    status_color = {"success": "#16a34a", "error": "#dc2626", "partial": "#d97706"}.get(
        ex.get("status", ""), "#666"
    )

    rows_fail = ""
    for f in data["failures"]:
        fix = _esc(str(f["fix_hint"] or "—"))
        rows_fail += (
            f"<tr><td>{_esc(str(f['server']))}</td>"
            f"<td>{_esc(str(f['name']))}</td>"
            f"<td>{_esc(str(f['reason']))}</td>"
            f"<td style='color:#16a34a'>{fix}</td></tr>"
        )

    rows_ai = ""
    for s in data["ai_suggestions"]:
        rows_ai += (
            f"<tr><td>{_esc(str(s['server']))}</td>"
            f"<td style='white-space:pre-wrap'>{_esc(str(s['message']))}</td></tr>"
        )

    rows_log = ""
    colors = {"success": "#16a34a", "error": "#dc2626", "warning": "#d97706", "info": "#2563eb"}
    for l in data["logs"][-100:]:
        c = colors.get(l["level"], "#666")
        ts = str(l.get("ts", ""))[:19].replace("T", " ")
        srv = _esc(str(l.get("server") or ""))
        rows_log += (
            f"<tr><td style='color:#888;font-size:11px'>{ts}</td>"
            f"<td style='color:{c};font-weight:500'>{_esc(str(l['level']))}</td>"
            f"<td style='color:#555'>{srv}</td>"
            f"<td>{_esc(str(l['message']))}</td></tr>"
        )

    agent_name = _esc(str(ex.get('agent_name', '')))
    status_txt = _esc(str(ex.get('status', '')))
    summary_txt = _esc(str(ex.get('summary', '')))

    return f"""<!DOCTYPE html><html lang="pt-BR"><head>
<meta charset="UTF-8">
<title>Relatório — {agent_name}</title>
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
  <h1>{agent_name} <span class="badge">{status_txt.upper()}</span></h1>
  <div class="meta">
    <span>Início: {started}</span>
    <span>Duração: {dur}</span>
    <span class="ok">✓ {ex.get('passed_checks',0)} OK</span>
    <span class="err">✗ {ex.get('failed_checks',0)} falhas</span>
    <span>Total: {ex.get('total_checks',0)}</span>
  </div>
  <p style="font-size:13px;color:#4a5568">{summary_txt}</p>
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
  VoipIA Agentes · Gerado em {started}
</p>
</body></html>"""
