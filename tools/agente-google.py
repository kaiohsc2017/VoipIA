#!/usr/bin/env python3
"""
agente-google.py
Agente especialista VoipIA com memória persistente via PostgreSQL.
Variante Google Gemini — usa a SDK google-genai diretamente.

Refatoração V2:
  - PERF: Implementada Sliding Window para histórico de mensagens (preserva tokens).
  - SEC: Removido parser manual de .env para usar python-dotenv (blindado).
  - STABILITY: Circuit breaker adicionado para evitar loop infinito de tools.
  - STABILITY: Tratamento de safety filters (IndexError) em response.candidates.
  - STABILITY: Conexão resiliente com banco via pool implícito na função RAG.
"""

import subprocess, os, sys, json, textwrap, re
from pathlib import Path
from datetime import datetime, timezone

try:
    import psycopg2
    import psycopg2.extras
    from dotenv import load_dotenv
except ImportError:
    print("❌  pip install psycopg2-binary python-dotenv")
    sys.exit(1)

# ─── Config ───────────────────────────────────────────────────────────────────

# Carrega variáveis de ambiente de forma segura
load_dotenv(Path("/opt/VoipIA/env/.env"))
load_dotenv(Path("/opt/VoipIA/.env"))
load_dotenv() # Fallback local

PROJECT_DIR = Path(os.environ.get("VOIPIA_DIR", "/opt/VoipIA"))
MODEL       = os.environ.get("GEMINI_MODEL_LLM", "gemini-2.5-flash")
MEMORY_TOP  = 6
SESSION_ID  = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
MAX_HISTORY_TURNS = 10     # Previne estouro de contexto
MAX_TOOL_ITERATIONS = 5    # Previne loop infinito do agente

# ─── Cores ────────────────────────────────────────────────────────────────────

class C:
    R="\033[0m"; B="\033[1m"; D="\033[2m"
    NAVY  ="\033[38;2;30;50;112m";  BLUE ="\033[38;2;45;79;214m"
    GREEN ="\033[38;2;34;197;94m";  YELL ="\033[38;2;234;179;8m"
    RED   ="\033[38;2;239;68;68m";  GRAY ="\033[38;2;107;114;128m"
    CYAN  ="\033[38;2;6;182;212m"

# ─── Banco de memória ─────────────────────────────────────────────────────────

SCHEMA = """
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS agent_fixes (
    id          SERIAL PRIMARY KEY,
    session_id  TEXT NOT NULL,
    title       TEXT NOT NULL,
    problem     TEXT NOT NULL,
    root_cause  TEXT,
    fix_applied TEXT NOT NULL,
    files_changed TEXT[],
    commands_run  TEXT[],
    succeeded   BOOLEAN NOT NULL DEFAULT true,
    notes       TEXT,
    tags        TEXT[],
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_error_patterns (
    id          SERIAL PRIMARY KEY,
    pattern     TEXT NOT NULL UNIQUE,
    cause       TEXT NOT NULL,
    solution    TEXT NOT NULL,
    seen_count  INT DEFAULT 1,
    last_seen   TIMESTAMPTZ DEFAULT NOW(),
    tags        TEXT[]
);

CREATE TABLE IF NOT EXISTS agent_preferences (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_project_state (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_sessions (
    id          SERIAL PRIMARY KEY,
    session_id  TEXT NOT NULL UNIQUE,
    summary     TEXT NOT NULL,
    actions     TEXT[],
    outcome     TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fixes_problem   ON agent_fixes   USING gin(problem gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_fixes_title     ON agent_fixes   USING gin(title   gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_fixes_tags      ON agent_fixes   USING gin(tags);
CREATE INDEX IF NOT EXISTS idx_patterns_pat    ON agent_error_patterns USING gin(pattern gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_sessions_summ   ON agent_sessions USING gin(summary gin_trgm_ops);
"""

class Memory:
    def __init__(self, dsn: str):
        self.dsn = dsn
        self.conn = None
        self._connect()
        self._migrate()
        self.preferences_cache: list[str]    = []
        self.project_state_cache: list[str]  = []
        self.sessions_cache: list[str]       = []
        self._load_caches()

    def _connect(self):
        try:
            self.conn = psycopg2.connect(
                self.dsn,
                connect_timeout=5,
                options="-c statement_timeout=2000",
            )
            self.conn.autocommit = True
        except Exception as e:
            print(f"{C.RED}✗ Memória indisponível (PostgreSQL): {e}{C.R}")
            self.conn = None

    def _migrate(self):
        if not self.conn: return
        try:
            with self.conn.cursor() as cur:
                cur.execute(SCHEMA)
        except Exception as e:
            print(f"{C.YELL}⚠ Migração: {e}{C.R}")

    def _load_caches(self):
        if not self.conn: return
        try:
            rows = self._q("SELECT key, value FROM agent_preferences", fetch=True)
            self.preferences_cache = [f"- {r['key']}: {r['value']}" for r in rows] if rows else []

            rows = self._q(
                "SELECT key, value FROM agent_project_state ORDER BY updated_at DESC LIMIT 10",
                fetch=True,
            )
            self.project_state_cache = [f"- {r['key']}: {r['value']}" for r in rows] if rows else []

            rows = self._q(
                "SELECT session_id, summary, outcome FROM agent_sessions ORDER BY created_at DESC LIMIT 3",
                fetch=True,
            )
            self.sessions_cache = [
                f"- [{r['session_id']}] {r['summary']}" + (f" → {r['outcome']}" if r["outcome"] else "")
                for r in rows
            ] if rows else []
        except Exception as e:
            print(f"{C.YELL}⚠ Erro ao carregar cache: {e}{C.R}")

    def _q(self, sql: str, params=(), fetch=False):
        if not self.conn: return [] if fetch else None
        try:
            with self.conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
                cur.execute(sql, params)
                return list(cur.fetchall()) if fetch else None
        except psycopg2.OperationalError:
            self._connect()
            if not self.conn:
                return [] if fetch else None
            try:
                with self.conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
                    cur.execute(sql, params)
                    return list(cur.fetchall()) if fetch else None
            except Exception:
                return [] if fetch else None
        except Exception:
            return [] if fetch else None

    # ── Busca RAG ─────────────────────────────────────────────────────────────

    def recall(self, query: str) -> str:
        if not self.conn or not query.strip():
            return ""

        chunks = []
        q_lower = query.lower().strip()
        is_simple_cmd = (
            len(q_lower) < 12 or
            q_lower.startswith(("execute:", "curl", "docker", "git",
                                "cat ", "cd ", "ls ", "grep", "tail", "head", "sed"))
        )

        if not is_simple_cmd:
            rows = self._q("""
                SELECT tipo, title, content, root_cause, fix_applied,
                       succeeded, notes, seen_count, score
                FROM (
                    SELECT
                        'fix'   AS tipo,
                        title,
                        problem AS content,
                        root_cause,
                        fix_applied,
                        succeeded,
                        notes,
                        NULL::int AS seen_count,
                        similarity(problem, %(q)s) AS score
                    FROM agent_fixes
                    WHERE problem %% %(q)s OR title %% %(q)s OR %(q)s = ANY(tags)

                    UNION ALL

                    SELECT
                        'pattern' AS tipo,
                        pattern   AS title,
                        cause     AS content,
                        NULL      AS root_cause,
                        solution  AS fix_applied,
                        NULL      AS succeeded,
                        NULL      AS notes,
                        seen_count,
                        similarity(pattern, %(q)s) AS score
                    FROM agent_error_patterns
                    WHERE pattern %% %(q)s
                ) sub
                ORDER BY score DESC
                LIMIT %(top)s
            """, {"q": query, "top": MEMORY_TOP}, fetch=True)

            if rows:
                fixes    = [r for r in rows if r["tipo"] == "fix"]
                patterns = [r for r in rows if r["tipo"] == "pattern"]

                if fixes:
                    chunks.append("## Fixes anteriores relevantes")
                    for r in fixes:
                        status = "✅ funcionou" if r["succeeded"] else "❌ não funcionou"
                        entry  = (
                            f"- **{r['title']}** ({status})\n"
                            f"  Problema: {r['content']}\n"
                            f"  Causa-raiz: {r['root_cause'] or 'não registrada'}\n"
                            f"  Fix: {r['fix_applied']}"
                        )
                        if r["notes"]:
                            entry += f"\n  Nota: {r['notes']}"
                        chunks.append(entry)

                if patterns:
                    chunks.append("## Padrões de erro conhecidos")
                    for r in patterns:
                        chunks.append(
                            f"- **Padrão:** {r['title']} (visto {r['seen_count']}x)\n"
                            f"  Causa: {r['content']}\n"
                            f"  Solução: {r['fix_applied']}"
                        )

        if self.project_state_cache:
            chunks.append("## Estado atual do projeto")
            chunks.extend(self.project_state_cache)

        if self.preferences_cache:
            chunks.append("## Preferências do usuário")
            chunks.extend(self.preferences_cache)

        if self.sessions_cache:
            chunks.append("## Sessões recentes")
            chunks.extend(self.sessions_cache)

        return "\n\n".join(chunks) if chunks else ""

    # ── Escrita de memórias ────────────────────────────────────────────────────

    def save_fix(self, data: dict):
        self._q("""
            INSERT INTO agent_fixes
                (session_id, title, problem, root_cause, fix_applied,
                 files_changed, commands_run, succeeded, notes, tags)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        """, (
            SESSION_ID,
            data.get("title", ""),
            data.get("problem", ""),
            data.get("root_cause"),
            data.get("fix_applied", ""),
            data.get("files_changed", []),
            data.get("commands_run", []),
            data.get("succeeded", True),
            data.get("notes"),
            data.get("tags", []),
        ))

    def save_error_pattern(self, data: dict):
        self._q("""
            INSERT INTO agent_error_patterns (pattern, cause, solution, tags)
            VALUES (%s,%s,%s,%s)
            ON CONFLICT (pattern) DO UPDATE
                SET cause=EXCLUDED.cause,
                    solution=EXCLUDED.solution,
                    seen_count=agent_error_patterns.seen_count+1,
                    last_seen=NOW()
        """, (data["pattern"], data["cause"], data["solution"], data.get("tags", [])))

    def save_preference(self, key: str, value: str):
        self._q("""
            INSERT INTO agent_preferences (key, value)
            VALUES (%s,%s)
            ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=NOW()
        """, (key, value))
        entry = f"- {key}: {value}"
        idx_map = {line.split(": ")[0][2:]: i for i, line in enumerate(self.preferences_cache)}
        if key in idx_map:
            self.preferences_cache[idx_map[key]] = entry
        else:
            self.preferences_cache.append(entry)

    def save_project_state(self, key: str, value: str):
        self._q("""
            INSERT INTO agent_project_state (key, value)
            VALUES (%s,%s)
            ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=NOW()
        """, (key, value))
        entry = f"- {key}: {value}"
        idx_map = {line.split(": ")[0][2:]: i for i, line in enumerate(self.project_state_cache)}
        if key in idx_map:
            self.project_state_cache[idx_map[key]] = entry
        else:
            self.project_state_cache.insert(0, entry)
            self.project_state_cache = self.project_state_cache[:10]

    def save_session(self, summary: str, actions: list, outcome: str):
        self._q("""
            INSERT INTO agent_sessions (session_id, summary, actions, outcome)
            VALUES (%s,%s,%s,%s)
            ON CONFLICT (session_id) DO UPDATE
                SET summary=EXCLUDED.summary, actions=EXCLUDED.actions, outcome=EXCLUDED.outcome
        """, (SESSION_ID, summary, actions, outcome))
        entry = f"- [{SESSION_ID}] {summary}" + (f" → {outcome}" if outcome else "")
        self.sessions_cache.insert(0, entry)
        self.sessions_cache = self.sessions_cache[:3]

    def stats(self) -> dict:
        if not self.conn: return {}
        rows = self._q("""
            SELECT relname AS tbl, n_live_tup AS cnt
            FROM pg_stat_user_tables
            WHERE relname IN (
                'agent_fixes', 'agent_error_patterns',
                'agent_sessions', 'agent_preferences', 'agent_project_state'
            )
        """, fetch=True)
        if not rows:
            return {}
        mapping = {
            "agent_fixes":          "fixes",
            "agent_error_patterns": "patterns",
            "agent_sessions":       "sessions",
            "agent_preferences":    "prefs",
            "agent_project_state":  "states",
        }
        return {mapping[r["tbl"]]: int(r["cnt"]) for r in rows if r["tbl"] in mapping}

# ─── Sistema de prompts ───────────────────────────────────────────────────────

BASE_SYSTEM = f"""Você é o Especialista Sênior do projeto VoipIA com 3 papéis simultâneos:
1. Desenvolvedor Sênior (Spring Boot 3, Java 21, Python asyncio, React 18)
2. Arquiteto DevOps (Docker Compose, Caddy, CI/CD, observabilidade)
3. Engenheiro Sênior Linux (Asterisk 21, redes, firewall, performance)

Stack: Spring Boot 3.3.5 · Java 21 · PostgreSQL 16 · React 18 · Python asyncio · Docker Compose · Asterisk 21 · Caddy
Projeto: {PROJECT_DIR}  |  Repo: https://github.com/kaiohsc2017/VoipIA
Containers: asterisk, ai-agent, backend, frontend, postgres, caddy, grafana, prometheus, scheduler, voipia-security
WebRTC: Caddy → Asterisk:8088 (WSS)  |  AudioSocket: porta 9092 (só rede Docker interna)

REGRAS DE EXECUÇÃO:
- Leitura (logs, ps, cat, grep, ss, git log/diff): execute SEM confirmação.
- Modificação (compose up/down, git pull, iptables, edição de arquivo): requires_confirmation=true.
- Fluxo: diagnóstico → causa-raiz → fix → executa com confirmação → valida.

MEMÓRIA:
- Use a ferramenta memory_write para registrar: fixes aplicados, padrões de erro, preferências do usuário, estado do projeto.
- Quando encontrar um erro já visto antes (estará no contexto de memória), mencione isso e use a solução conhecida diretamente.
- Ao fim de cada interação relevante, grave o que aprendeu.

Responda sempre em português brasileiro. Seja direto e técnico."""

def build_system_prompt(memory_context: str) -> str:
    if not memory_context:
        return BASE_SYSTEM
    return BASE_SYSTEM + f"\n\n---\n# MEMÓRIA RECUPERADA\n{memory_context}\n---"

# ─── Ferramentas ──────────────────────────────────────────────────────────────

NEEDS_CONFIRM_RE = re.compile(
    r"docker\s+compose\s+(up|down|restart|stop|rm)|"
    r"docker\s+(restart|stop|start|exec|rm)|"
    r"git\s+(pull|push|checkout|reset|rebase)|"
    r"iptables|nft|ufw|firewall|\brm\s+-|\bmv\s+|sed\s+-i|tee\s+|"
    r"systemctl\s+(restart|stop|start|enable|disable)|"
    r"apt(-get)?\s+install|"
    r"[^>]>\s*/|"
    r"drop\s+table|drop\s+database|truncate\s+table|delete\s+from",
    re.IGNORECASE,
)

def run_cmd(cmd: str) -> dict:
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                           timeout=120, cwd=str(PROJECT_DIR))
        out = r.stdout
        if r.stderr:
            out += ("\n[stderr]\n" if r.returncode != 0 else "") + r.stderr
        return {"success": r.returncode == 0, "returncode": r.returncode, "output": out[:8000]}
    except subprocess.TimeoutExpired:
        return {"success": False, "returncode": -1, "output": "Timeout (120s)"}
    except Exception as e:
        return {"success": False, "returncode": -1, "output": str(e)}

def print_tool(desc, cmd=""):
    print(f"\n  {C.YELL}⚡ {desc}{C.R}")
    if cmd: print(f"  {C.D}{cmd[:110]}{'…' if len(cmd) > 110 else ''}{C.R}")

def print_result(output, ok):
    icon = f"{C.GREEN}✓" if ok else f"{C.RED}✗"
    print(f"  {icon}{C.R}")
    for line in output.strip().split("\n")[:25]:
        print(f"  {C.D}{line}{C.R}")
    total = len(output.strip().split("\n"))
    if total > 25: print(f"  {C.GRAY}… {total} linhas{C.R}")

def confirm(label: str) -> bool:
    print(f"\n  {C.YELL}⚠  Requer confirmação: {label}{C.R}")
    return input(f"  {C.YELL}Executar? [s/N]{C.R} ").strip().lower() in ("s", "sim", "y", "yes")

def handle_bash(args: dict, mem: Memory) -> str:
    cmd  = args["command"]
    desc = args.get("description", cmd)
    must = args.get("requires_confirmation", False) or bool(NEEDS_CONFIRM_RE.search(cmd))
    print_tool(desc, cmd)
    if must and not confirm(cmd):
        return json.dumps({"success": False, "output": "Cancelado.", "returncode": -1})
    result = run_cmd(cmd)
    print_result(result["output"], result["success"])
    return json.dumps(result)

def handle_write_file(args: dict, mem: Memory) -> str:
    path, content = args["path"], args["content"]
    desc = args.get("description", f"Escrever {path}")
    print_tool(f"Escrever: {path}", desc)
    for line in content.split("\n")[:15]:
        print(f"  {C.D}{line}{C.R}")
    extra = len(content.split("\n")) - 15
    if extra > 0: print(f"  {C.GRAY}… +{extra} linhas{C.R}")
    if not confirm(f"Escrever: {path}"):
        return json.dumps({"success": False, "output": "Cancelado."})
    try:
        p = Path(path)
        if p.exists():
            bak = str(p) + f".bak.{datetime.now().strftime('%Y%m%d_%H%M%S')}"
            p.rename(bak)
            print(f"  {C.GREEN}Backup: {bak}{C.R}")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        print(f"  {C.GREEN}✓ Escrito: {path}{C.R}")
        return json.dumps({"success": True, "output": f"Arquivo salvo em {path}"})
    except Exception as e:
        return json.dumps({"success": False, "output": str(e)})

def handle_memory_write(args: dict, mem: Memory) -> str:
    mtype = args.get("type")
    data  = args.get("data", {})
    print_tool(f"Gravando memória [{mtype}]", json.dumps(data, ensure_ascii=False)[:80])
    try:
        if mtype == "fix":
            mem.save_fix(data)
        elif mtype == "error_pattern":
            mem.save_error_pattern(data)
        elif mtype == "preference":
            mem.save_preference(data["key"], data["value"])
        elif mtype == "project_state":
            mem.save_project_state(data["key"], data["value"])
        elif mtype == "session_summary":
            mem.save_session(data.get("summary", ""), data.get("actions", []), data.get("outcome", ""))
        else:
            return json.dumps({"success": False, "output": f"Tipo desconhecido: {mtype}"})
        print(f"  {C.CYAN}✓ Memória gravada{C.R}")
        return json.dumps({"success": True, "output": f"Memória [{mtype}] gravada."})
    except Exception as e:
        return json.dumps({"success": False, "output": str(e)})

# ─── UI ───────────────────────────────────────────────────────────────────────

def banner(mem: Memory):
    stats   = mem.stats()
    mem_line = ""
    if stats:
        mem_line = (
            f"  Memória: {stats.get('fixes', 0)} fixes · "
            f"{stats.get('patterns', 0)} padrões · "
            f"{stats.get('sessions', 0)} sessões"
        )
    print(f"""
{C.NAVY}{C.B}╔══════════════════════════════════════════════════════════╗
║     VoipIA · Especialista Sênior · Memória Ativa     ║
╚══════════════════════════════════════════════════════════╝{C.R}
{C.GRAY}  Projeto : {PROJECT_DIR}
  Modelo  : {MODEL}
  Sessão  : {SESSION_ID}{C.R}
{C.CYAN}{mem_line}{C.R}
{C.GRAY}  Digite sua pergunta ou 'sair' para encerrar.{C.R}
""")

def print_memory_hint(context: str):
    if not context:
        return
    count = sum(1 for line in context.strip().split("\n") if line.startswith("-"))
    print(f"  {C.CYAN}◈ {count} memórias relevantes carregadas{C.R}")

def print_agent(text: str):
    print(f"\n{C.NAVY}{C.B}◆ Agente{C.R}")
    in_code = False
    for line in text.split("\n"):
        if line.startswith("```"):
            in_code = not in_code
            bar = "┌" if in_code else "└"
            print(f"  {C.GRAY}{bar}{'─' * 47}{C.R}")
            continue
        pre = f"  {C.BLUE}" if in_code else "  "
        suf = C.R if in_code else ""
        for chunk in textwrap.wrap(line, 76) or [""]:
            print(f"{pre}{chunk}{suf}")
    print()

# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print(f"{C.RED}✗ GEMINI_API_KEY não encontrada no ambiente ou .env{C.R}")
        sys.exit(1)

    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        db_host = os.environ.get("DB_HOST", "localhost")
        db_port = os.environ.get("DB_PORT", os.environ.get("POSTGRES_PORT", "5432"))
        db_name = os.environ.get("DB_NAME", os.environ.get("POSTGRES_DB", "asteriskia"))
        db_user = os.environ.get("DB_USER", os.environ.get("POSTGRES_USER", "asteriskia"))
        db_pass = os.environ.get("DB_PASS", os.environ.get("POSTGRES_PASSWORD", ""))
        dsn = f"host={db_host} port={db_port} dbname={db_name} user={db_user} password={db_pass}"

    mem = Memory(dsn)
    banner(mem)

    from google import genai
    from google.genai import types

    client = genai.Client(api_key=api_key)

    TOOL_DECLARATIONS = [
        types.FunctionDeclaration(
            name="bash",
            description=(
                "Executa comando bash no VPS de produção do VoipIA. "
                "Leitura: execute direto. Modificação: requires_confirmation=true."
            ),
            parameters={"type": "object", "properties": {
                "command":              {"type": "string", "description": "Comando bash a executar"},
                "description":          {"type": "string", "description": "O que o comando faz"},
                "requires_confirmation": {"type": "boolean", "description": "True para comandos destrutivos"},
            }, "required": ["command", "description"]},
        ),
        types.FunctionDeclaration(
            name="write_file",
            description="Escreve/sobrescreve arquivo no VPS. Sempre pede confirmação e faz backup.",
            parameters={"type": "object", "properties": {
                "path":        {"type": "string", "description": "Caminho absoluto"},
                "content":     {"type": "string", "description": "Conteúdo completo"},
                "description": {"type": "string", "description": "Descrição da mudança"},
            }, "required": ["path", "content", "description"]},
        ),
        types.FunctionDeclaration(
            name="memory_write",
            description=(
                "Grava uma memória persistente no banco. Use para registrar aprendizados da sessão: "
                "fixes aplicados, padrões de erro descobertos, preferências do usuário, "
                "estado atualizado do projeto, ou resumo da sessão. "
                "Chame isso sempre que aprender algo novo ou resolver um problema."
            ),
            parameters={"type": "object", "properties": {
                "type": {
                    "type": "string",
                    "enum": ["fix", "error_pattern", "preference", "project_state", "session_summary"],
                    "description": "Tipo de memória a gravar",
                },
                "data": {
                    "type": "object",
                    "description": (
                        "Dados da memória. "
                        "fix: {title, problem, root_cause, fix_applied, files_changed[], commands_run[], succeeded, notes, tags[]} | "
                        "error_pattern: {pattern, cause, solution, tags[]} | "
                        "preference: {key, value} | "
                        "project_state: {key, value} | "
                        "session_summary: {summary, actions[], outcome}"
                    ),
                },
            }, "required": ["type", "data"]},
        ),
    ]
    TOOLS_LIST = [types.Tool(function_declarations=TOOL_DECLARATIONS)]

    print(f"{C.GRAY}Coletando estado dos containers…{C.R}")
    r = run_cmd("docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'")
    if r["success"]:
        for line in r["output"].strip().split("\n"):
            print(f"  {C.D}{line}{C.R}")
    print()

    history: list[types.Content] = []
    session_actions: list[str]   = []

    def make_config(query: str) -> types.GenerateContentConfig:
        mem_ctx = mem.recall(query)
        print_memory_hint(mem_ctx)
        return types.GenerateContentConfig(
            system_instruction=build_system_prompt(mem_ctx),
            tools=TOOLS_LIST,
            max_output_tokens=8192,
            temperature=0.15,
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        )

    while True:
        try:
            user_text = input(f"\n{C.BLUE}{C.B}▶ Você{C.R} {C.GRAY}(ou 'sair'){C.R}\n  ").strip()
        except (KeyboardInterrupt, EOFError):
            user_text = "sair"

        if not user_text:
            continue

        if user_text.lower() in ("sair", "exit", "quit", "q"):
            if session_actions:
                print(f"\n{C.GRAY}Gravando resumo da sessão…{C.R}")
                try:
                    summary_prompt = (
                        f"Resuma em 1-2 frases o que foi feito nesta sessão. "
                        f"Ações realizadas: {'; '.join(session_actions[-10:])}"
                    )
                    sr = client.models.generate_content(
                        model=MODEL,
                        contents=summary_prompt,
                        config=types.GenerateContentConfig(max_output_tokens=200, temperature=0.1),
                    )
                    summary = sr.text.strip()
                    mem.save_session(summary, session_actions[-20:], "encerrado pelo usuário")
                    print(f"  {C.CYAN}✓ Sessão gravada: {summary[:80]}{C.R}")
                except Exception:
                    pass
            print(f"\n{C.GRAY}Até logo!{C.R}")
            break

        history.append(types.Content(role="user", parts=[types.Part(text=user_text)]))
        session_actions.append(f"pergunta: {user_text[:60]}")

        # Sliding Window de histórico para proteger contra max tokens e custo
        if len(history) > MAX_HISTORY_TURNS:
            history = history[-MAX_HISTORY_TURNS:]

        config = make_config(user_text)

        tool_iterations = 0

        while True:
            tool_iterations += 1
            if tool_iterations > MAX_TOOL_ITERATIONS:
                print(f"\n{C.RED}✗ Limite de ferramentas excedido. Intervenção manual necessária.{C.R}")
                history.append(types.Content(role="user", parts=[types.Part(text="Erro: Loop de ferramenta detectado. Pare e aguarde novas instruções.")]))
                break

            try:
                response = client.models.generate_content(
                    model=MODEL, contents=history, config=config,
                )
            except Exception as e:
                print(f"\n{C.RED}✗ Erro API: {e}{C.R}")
                history.pop()
                break

            if not response.candidates:
                print(f"\n{C.RED}✗ Resposta bloqueada ou vazia (Safety Filter / Max Tokens).{C.R}")
                history.pop()
                break

            candidate = response.candidates[0]
            if candidate.content is None or not candidate.content.parts:
                print(f"\n{C.RED}✗ Conteúdo vazio retornado (finish_reason={candidate.finish_reason.name}){C.R}")
                history.pop()
                break
                
            parts      = candidate.content.parts
            text_parts = [p.text for p in parts if p.text]
            tool_calls = [p.function_call for p in parts if p.function_call]

            if text_parts:
                print_agent("\n".join(t for t in text_parts if t))

            if not tool_calls:
                history.append(types.Content(role="model", parts=parts))
                break

            history.append(types.Content(role="model", parts=parts))

            response_parts = []
            for fc in tool_calls:
                fn_args = dict(fc.args)

                if fc.name == "bash":
                    result = handle_bash(fn_args, mem)
                    session_actions.append(f"bash: {fn_args.get('command', '')[:60]}")
                elif fc.name == "write_file":
                    result = handle_write_file(fn_args, mem)
                    session_actions.append(f"write_file: {fn_args.get('path', '')}")
                elif fc.name == "memory_write":
                    result = handle_memory_write(fn_args, mem)
                    session_actions.append(f"memory_write[{fn_args.get('type', '')}]")
                else:
                    result = json.dumps({"error": f"ferramenta desconhecida: {fc.name}"})

                response_parts.append(types.Part(
                    function_response=types.FunctionResponse(
                        name=fc.name,
                        response={"result": result},
                    )
                ))

            history.append(types.Content(role="user", parts=response_parts))

            if candidate.finish_reason.name not in ("STOP", "MAX_TOKENS", ""):
                break

if __name__ == "__main__":
    main()
