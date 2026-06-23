#!/usr/bin/env python3
"""
asteriskia-agent.py
Agente especialista AsteriskIA com memória persistente via PostgreSQL.

Papéis: Desenvolvedor Sênior · Arquiteto DevOps · Engenheiro Sênior Linux

Memória (RAG simples via pg_trgm):
  - agent_fixes          : correções aplicadas e se funcionaram
  - agent_error_patterns : padrões de erro e causas-raiz conhecidas
  - agent_preferences    : preferências do usuário
  - agent_project_state  : estado atual do projeto (versões, configs, pendências)
  - agent_sessions       : histórico resumido de sessões anteriores

Uso:
    pip install google-genai psycopg2-binary
    python3 tools/asteriskia-agent.py

A GEMINI_API_KEY e DATABASE_URL são lidas do .env do projeto automaticamente.
"""

import os
import sys
import json
import re
import subprocess
import textwrap
from pathlib import Path
from datetime import datetime, timezone

try:
    from google import genai
    from google.genai import types
except ImportError:
    print("❌  pip install google-genai")
    sys.exit(1)

try:
    import psycopg2
    import psycopg2.extras
except ImportError:
    print("❌  pip install psycopg2-binary")
    sys.exit(1)

# ─── Config ───────────────────────────────────────────────────────────────────

PROJECT_DIR = Path(os.environ.get("ASTERISKIA_DIR", "/opt/AsteriskIA"))
MODEL       = "gemini-2.5-flash"
MEMORY_TOP  = 6
SESSION_ID  = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")

# ─── Cores ────────────────────────────────────────────────────────────────────

class C:
    R    = "\033[0m";  B    = "\033[1m";  D    = "\033[2m"
    NAVY = "\033[38;2;30;50;112m"
    BLUE = "\033[38;2;45;79;214m"
    GREEN= "\033[38;2;34;197;94m"
    YELL = "\033[38;2;234;179;8m"
    RED  = "\033[38;2;239;68;68m"
    GRAY = "\033[38;2;107;114;128m"
    CYAN = "\033[38;2;6;182;212m"

# ─── .env loader ──────────────────────────────────────────────────────────────

def load_env():
    """Carrega variáveis do .env do projeto AsteriskIA."""
    env_paths = [
        PROJECT_DIR / "env" / ".env",
        PROJECT_DIR / ".env",
        Path(".env"),
    ]
    for p in env_paths:
        if p.exists():
            with open(p) as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    k = k.strip()
                    v = v.strip().strip('"').strip("'")
                    if k and k not in os.environ:
                        os.environ[k] = v
            return str(p)
    return None

# ─── PostgreSQL ───────────────────────────────────────────────────────────────

def _connect():
    url = os.environ.get("DATABASE_URL") or os.environ.get("POSTGRES_URL")
    if url:
        return psycopg2.connect(url, connect_timeout=5,
                                options="-c statement_timeout=2000")
    return psycopg2.connect(
        host    = os.environ.get("POSTGRES_HOST", "localhost"),
        port    = int(os.environ.get("POSTGRES_PORT", 5432)),
        dbname  = os.environ.get("POSTGRES_DB",   "asteriskia"),
        user    = os.environ.get("POSTGRES_USER",  "asteriskia"),
        password= os.environ.get("POSTGRES_PASSWORD", ""),
        connect_timeout=5,
        options ="-c statement_timeout=2000",
    )

def _q(sql: str, params=(), fetchall=True):
    with _connect() as conn:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
            if fetchall:
                return list(cur.fetchall())
            conn.commit()
            return []

def init_schema():
    """Cria tabelas de memória se não existirem."""
    ddl = """
    CREATE EXTENSION IF NOT EXISTS pg_trgm;

    CREATE TABLE IF NOT EXISTS agent_fixes (
        id         SERIAL PRIMARY KEY,
        problem    TEXT NOT NULL,
        title      TEXT,
        fix        TEXT NOT NULL,
        worked     BOOLEAN DEFAULT TRUE,
        tags       TEXT[],
        created_at TIMESTAMPTZ DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS idx_agent_fixes_problem ON agent_fixes
        USING GIN (problem gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS idx_agent_fixes_title ON agent_fixes
        USING GIN (title gin_trgm_ops);

    CREATE TABLE IF NOT EXISTS agent_error_patterns (
        id         SERIAL PRIMARY KEY,
        pattern    TEXT NOT NULL,
        root_cause TEXT,
        solution   TEXT,
        created_at TIMESTAMPTZ DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS idx_agent_patterns ON agent_error_patterns
        USING GIN (pattern gin_trgm_ops);

    CREATE TABLE IF NOT EXISTS agent_preferences (
        key        TEXT PRIMARY KEY,
        value      TEXT NOT NULL,
        updated_at TIMESTAMPTZ DEFAULT now()
    );

    CREATE TABLE IF NOT EXISTS agent_project_state (
        key        TEXT PRIMARY KEY,
        value      TEXT NOT NULL,
        updated_at TIMESTAMPTZ DEFAULT now()
    );

    CREATE TABLE IF NOT EXISTS agent_sessions (
        id         SERIAL PRIMARY KEY,
        session_id TEXT NOT NULL,
        summary    TEXT NOT NULL,
        created_at TIMESTAMPTZ DEFAULT now()
    );
    """
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(ddl)
        conn.commit()

# ─── Memória ──────────────────────────────────────────────────────────────────

_mem_cache: dict = {}

def recall(query: str) -> str:
    """Busca memórias relevantes para a query (RAG simples via pg_trgm)."""
    try:
        rows = _q("""
            SELECT 'fix' AS type, title AS label, fix AS content, worked::text AS extra
            FROM agent_fixes
            WHERE problem %% %s OR title %% %s
            ORDER BY similarity(problem, %s) DESC
            LIMIT %s

            UNION ALL

            SELECT 'pattern' AS type, pattern AS label, solution AS content, root_cause AS extra
            FROM agent_error_patterns
            WHERE pattern %% %s
            ORDER BY similarity(pattern, %s) DESC
            LIMIT %s
        """, (query, query, query, MEMORY_TOP // 2,
              query, query, MEMORY_TOP // 2))

        prefs = _mem_cache.get("preferences") or _q(
            "SELECT key, value FROM agent_preferences"
        )
        _mem_cache["preferences"] = prefs

        state = _mem_cache.get("project_state") or _q(
            "SELECT key, value FROM agent_project_state"
        )
        _mem_cache["project_state"] = state

        sessions = _q(
            "SELECT summary FROM agent_sessions ORDER BY created_at DESC LIMIT 3"
        )

        parts = []
        if rows:
            parts.append("## Correções e padrões relevantes")
            for r in rows:
                tag = "✅" if r.get("extra") == "true" else ("❌" if r.get("type") == "fix" else "🔍")
                parts.append(f"{tag} **{r['label']}**: {r['content']}")

        if prefs:
            parts.append("\n## Preferências")
            for p in prefs:
                parts.append(f"- {p['key']}: {p['value']}")

        if state:
            parts.append("\n## Estado do projeto")
            for s in state:
                parts.append(f"- {s['key']}: {s['value']}")

        if sessions:
            parts.append("\n## Sessões anteriores")
            for s in sessions:
                parts.append(f"- {s['summary']}")

        return "\n".join(parts) if parts else ""
    except Exception as e:
        return f"[memória indisponível: {e}]"

def save_fix(problem: str, fix: str, worked: bool = True, title: str = "", tags: list = None):
    _q("INSERT INTO agent_fixes (problem, title, fix, worked, tags) VALUES (%s,%s,%s,%s,%s)",
       (problem, title, fix, worked, tags or []), fetchall=False)

def save_pattern(pattern: str, root_cause: str, solution: str):
    _q("""INSERT INTO agent_error_patterns (pattern, root_cause, solution)
         VALUES (%s,%s,%s)
         ON CONFLICT DO NOTHING""",
       (pattern, root_cause, solution), fetchall=False)

def save_preference(key: str, value: str):
    _q("""INSERT INTO agent_preferences (key, value, updated_at)
         VALUES (%s,%s,now())
         ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()""",
       (key, value), fetchall=False)
    if "preferences" in _mem_cache:
        for p in _mem_cache["preferences"]:
            if p["key"] == key:
                p["value"] = value
                return
        _mem_cache["preferences"].append({"key": key, "value": value})

def save_project_state(key: str, value: str):
    _q("""INSERT INTO agent_project_state (key, value, updated_at)
         VALUES (%s,%s,now())
         ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()""",
       (key, value), fetchall=False)
    if "project_state" in _mem_cache:
        for s in _mem_cache["project_state"]:
            if s["key"] == key:
                s["value"] = value
                return
        _mem_cache["project_state"].append({"key": key, "value": value})

def save_session(summary: str):
    _q("INSERT INTO agent_sessions (session_id, summary) VALUES (%s,%s)",
       (SESSION_ID, summary), fetchall=False)

def stats() -> str:
    try:
        rows = _q("""
            SELECT relname AS table, n_live_tup AS rows
            FROM pg_stat_user_tables
            WHERE relname LIKE 'agent_%'
            ORDER BY relname
        """)
        return " | ".join(f"{r['table'].replace('agent_','')}: {r['rows']}" for r in rows)
    except Exception as e:
        return f"erro: {e}"

# ─── System prompt ────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """Você é o AsteriskIA Agent — especialista técnico com acesso ao projeto AsteriskIA.

**Stack:** Asterisk 21 LTS · Spring Boot 3.3.5 · React 18 + TypeScript · Python asyncio · PostgreSQL 16 · Docker Compose · Caddy

**Papéis simultâneos:**
- Desenvolvedor Sênior (Java/Spring Boot, Python, TypeScript)
- Arquiteto DevOps (Docker, Linux, redes)
- Engenheiro Sênior Linux (Asterisk, SIP/RTP, WebRTC, nftables)

**Regras:**
- Respostas diretas e técnicas. Sem introduções desnecessárias.
- Para problemas: diagnóstico → causa raiz → solução → como verificar.
- Código sempre completo, nunca truncado com "...".
- Quando aplicar uma correção, registre na memória para sessões futuras.
- Fale em português.

**Ferramentas disponíveis:**
- save_fix(problem, fix, worked, title, tags): registra correção aplicada
- save_pattern(pattern, root_cause, solution): registra padrão de erro
- save_preference(key, value): salva preferência do usuário
- save_project_state(key, value): atualiza estado do projeto
"""

# ─── Gemini tools ─────────────────────────────────────────────────────────────

TOOLS = types.Tool(function_declarations=[
    types.FunctionDeclaration(
        name="save_fix",
        description="Registra uma correção aplicada no projeto para memória futura.",
        parameters=types.Schema(
            type="OBJECT",
            properties={
                "problem":  types.Schema(type="STRING", description="Descrição do problema"),
                "fix":      types.Schema(type="STRING", description="Correção aplicada"),
                "worked":   types.Schema(type="BOOLEAN", description="Se funcionou"),
                "title":    types.Schema(type="STRING", description="Título curto"),
                "tags":     types.Schema(type="ARRAY", items=types.Schema(type="STRING")),
            },
            required=["problem", "fix"],
        ),
    ),
    types.FunctionDeclaration(
        name="save_pattern",
        description="Registra um padrão de erro e sua causa raiz.",
        parameters=types.Schema(
            type="OBJECT",
            properties={
                "pattern":    types.Schema(type="STRING"),
                "root_cause": types.Schema(type="STRING"),
                "solution":   types.Schema(type="STRING"),
            },
            required=["pattern", "root_cause", "solution"],
        ),
    ),
    types.FunctionDeclaration(
        name="save_preference",
        description="Salva preferência do usuário.",
        parameters=types.Schema(
            type="OBJECT",
            properties={
                "key":   types.Schema(type="STRING"),
                "value": types.Schema(type="STRING"),
            },
            required=["key", "value"],
        ),
    ),
    types.FunctionDeclaration(
        name="save_project_state",
        description="Atualiza estado do projeto (versão, config, pendência).",
        parameters=types.Schema(
            type="OBJECT",
            properties={
                "key":   types.Schema(type="STRING"),
                "value": types.Schema(type="STRING"),
            },
            required=["key", "value"],
        ),
    ),
])

def handle_tool_call(name: str, args: dict) -> str:
    if name == "save_fix":
        save_fix(
            args.get("problem", ""),
            args.get("fix", ""),
            args.get("worked", True),
            args.get("title", ""),
            args.get("tags", []),
        )
        return "✅ Correção registrada na memória."
    elif name == "save_pattern":
        save_pattern(args["pattern"], args["root_cause"], args["solution"])
        return "✅ Padrão registrado."
    elif name == "save_preference":
        save_preference(args["key"], args["value"])
        return f"✅ Preferência '{args['key']}' salva."
    elif name == "save_project_state":
        save_project_state(args["key"], args["value"])
        return f"✅ Estado '{args['key']}' atualizado."
    return f"[tool desconhecida: {name}]"

# ─── UI helpers ───────────────────────────────────────────────────────────────

def banner():
    print(f"""
{C.NAVY}{C.B}╔══════════════════════════════════════════╗
║  AsteriskIA Agent  ·  v1.0               ║
║  Especialista técnico com memória        ║
╚══════════════════════════════════════════╝{C.R}
{C.GRAY}Modelo: {MODEL} · Sessão: {SESSION_ID}{C.R}
{C.GRAY}Comandos: /mem  /stats  /fix  /state  /sair{C.R}
""")

def print_assistant(text: str):
    print(f"\n{C.BLUE}{C.B}◆ Agente{C.R}")
    # Renderiza code blocks em destaque
    parts = re.split(r'(```[^\n]*\n.*?```)', text, flags=re.DOTALL)
    for part in parts:
        if part.startswith("```"):
            lines = part.split("\n")
            lang = lines[0].replace("```", "").strip()
            code = "\n".join(lines[1:-1])
            print(f"{C.GRAY}┌─ {lang or 'code'} {'─'*(38-len(lang))}┐{C.R}")
            for line in code.split("\n"):
                print(f"{C.GRAY}│{C.R} {C.CYAN}{line}{C.R}")
            print(f"{C.GRAY}└{'─'*40}┘{C.R}")
        else:
            # Bold markdown
            part = re.sub(r'\*\*(.+?)\*\*', f'{C.B}\\1{C.R}', part)
            print(part, end="")
    print()

# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    env_path = load_env()

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print(f"{C.RED}❌ GEMINI_API_KEY não encontrada.{C.R}")
        print(f"   Configure em: {PROJECT_DIR}/env/.env")
        sys.exit(1)

    # Inicializa schema e cliente
    try:
        init_schema()
        db_ok = True
    except Exception as e:
        print(f"{C.YELL}⚠ Memória indisponível: {e}{C.R}")
        db_ok = False

    client = genai.Client(api_key=api_key)

    banner()
    if env_path:
        print(f"{C.GRAY}.env carregado: {env_path}{C.R}")
    if db_ok:
        print(f"{C.GREEN}✓ Memória PostgreSQL conectada{C.R}  {C.GRAY}({stats()}){C.R}")
    print()

    history = []
    session_turns = []

    while True:
        try:
            user_input = input(f"{C.B}{C.NAVY}Você ▶{C.R} ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break

        if not user_input:
            continue

        # Comandos especiais
        if user_input.lower() in ("/sair", "/exit", "/quit"):
            break

        if user_input.lower() == "/stats":
            print(f"{C.GRAY}{stats()}{C.R}")
            continue

        if user_input.lower() == "/mem":
            mem = recall(session_turns[-1] if session_turns else "asterisk docker")
            print(f"\n{C.GRAY}{mem or 'Nenhuma memória encontrada.'}{C.R}\n")
            continue

        if user_input.lower().startswith("/fix "):
            parts = user_input[5:].split("|")
            if len(parts) >= 2:
                save_fix(parts[0].strip(), parts[1].strip(), True,
                         parts[2].strip() if len(parts) > 2 else "")
                print(f"{C.GREEN}✓ Fix registrado{C.R}")
            else:
                print(f"{C.GRAY}Uso: /fix problema | solução | título{C.R}")
            continue

        if user_input.lower().startswith("/state "):
            parts = user_input[7:].split("=", 1)
            if len(parts) == 2:
                save_project_state(parts[0].strip(), parts[1].strip())
                print(f"{C.GREEN}✓ Estado atualizado{C.R}")
            else:
                print(f"{C.GRAY}Uso: /state chave=valor{C.R}")
            continue

        session_turns.append(user_input)

        # Busca memórias relevantes
        memory_context = recall(user_input) if db_ok else ""

        # Monta conteúdo do usuário com contexto de memória
        user_content = user_input
        if memory_context:
            user_content = f"[Contexto da memória]\n{memory_context}\n\n[Pergunta]\n{user_input}"

        history.append(types.Content(
            role="user",
            parts=[types.Part(text=user_content)]
        ))

        print(f"\n{C.GRAY}●{C.R}", end="", flush=True)

        try:
            while True:
                response = client.models.generate_content(
                    model=MODEL,
                    contents=history,
                    config=types.GenerateContentConfig(
                        system_instruction=SYSTEM_PROMPT,
                        tools=[TOOLS],
                        temperature=0.2,
                        max_output_tokens=8192,
                    ),
                )

                print(f"\r{' '*2}\r", end="")

                candidate = response.candidates[0]
                response_parts = []
                text_parts = []
                tool_calls = []

                for part in candidate.content.parts:
                    if hasattr(part, "text") and part.text:
                        text_parts.append(part.text)
                        response_parts.append(part)
                    elif hasattr(part, "function_call") and part.function_call:
                        tool_calls.append(part.function_call)
                        response_parts.append(part)

                if text_parts:
                    print_assistant("".join(text_parts))

                if not tool_calls:
                    history.append(types.Content(
                        role="model",
                        parts=response_parts
                    ))
                    break

                # Executa tool calls
                history.append(types.Content(role="model", parts=response_parts))

                tool_results = []
                for fc in tool_calls:
                    result = handle_tool_call(fc.name, dict(fc.args))
                    print(f"{C.GRAY}  → {fc.name}: {result}{C.R}")
                    tool_results.append(types.Part(
                        function_response=types.FunctionResponse(
                            name=fc.name,
                            response={"result": result}
                        )
                    ))

                history.append(types.Content(role="user", parts=tool_results))
                print(f"{C.GRAY}●{C.R}", end="", flush=True)

        except Exception as e:
            print(f"\n{C.RED}❌ Erro: {e}{C.R}\n")
            if history and history[-1].role == "user":
                history.pop()
            continue

    # Salva resumo da sessão
    if db_ok and session_turns:
        print(f"\n{C.GRAY}Salvando sessão...{C.R}", end="", flush=True)
        try:
            summary_resp = client.models.generate_content(
                model=MODEL,
                contents=[types.Content(
                    role="user",
                    parts=[types.Part(text=(
                        "Resuma em 2-3 linhas o que foi discutido e resolvido nesta sessão:\n\n"
                        + "\n".join(session_turns[:10])
                    ))]
                )],
                config=types.GenerateContentConfig(max_output_tokens=200),
            )
            summary = summary_resp.candidates[0].content.parts[0].text
            save_session(summary)
            print(f"\r{C.GREEN}✓ Sessão gravada{C.R}      ")
        except Exception as e:
            print(f"\r{C.GRAY}(sessão não salva: {e}){C.R}")

    print(f"\n{C.NAVY}Até logo!{C.R}\n")


if __name__ == "__main__":
    main()
