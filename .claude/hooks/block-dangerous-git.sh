#!/bin/bash
# Hook PreToolUse (matcher: Bash) — bloqueia comandos destrutivos antes da execução.
# Adaptado das "Regras inegociáveis em produção" do CLAUDE.md deste repositório.
#
# Importante: NÃO bloqueia "git push" comum nem "docker compose down" seguido de "up"
# na mesma linha — ambos fazem parte do fluxo de deploy documentado no CLAUDE.md.

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

if [ -z "$COMMAND" ]; then
  exit 0
fi

# Pré-processamento em Python (mais confiável que awk/sed para isso):
# 1) Remove o CORPO de heredocs (ex.: mensagem de commit via "cat <<'EOF' ... EOF")
#    — é dado consumido por `cat`, não comando executado.
# 2) Mascara o conteúdo dentro de aspas simples — em bash, aspas simples são
#    SEMPRE literais (nunca executadas), então texto de prosa entre elas
#    (ex.: `git commit -m 'menciona docker compose down'`) não deve casar.
# Sem isso, qualquer prosa que cite os nomes desses comandos dispara falso positivo.
export HOOK_SCAN_COMMAND="$COMMAND"
SCAN=$(python3 - <<'PYEOF'
import os, re, sys

# Lido via variável de ambiente, não stdin: o heredoc acima já ocupa o stdin
# do processo Python como código-fonte do script (python3 -), então um pipe
# concorrente de dados pelo stdin nunca chegaria a ser lido.
s = os.environ.get("HOOK_SCAN_COMMAND", "")

# --- 1) remove corpo de heredocs ---
lines = s.split("\n")
heredoc_re = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")
out = []
i = 0
while i < len(lines):
    line = lines[i]
    out.append(line)
    m = heredoc_re.search(line)
    if m:
        delim = m.group(2)
        i += 1
        while i < len(lines) and lines[i].strip() != delim:
            i += 1
        i += 1  # pula a linha do delimitador também
        continue
    i += 1
s = "\n".join(out)

# --- 2) mascara conteúdo entre aspas simples (sempre literal em bash) ---
masked = []
in_squote = False
for c in s:
    if in_squote:
        if c == "'":
            in_squote = False
            masked.append(c)
        else:
            masked.append(" ")
        continue
    if c == "'":
        in_squote = True
        masked.append(c)
        continue
    masked.append(c)

sys.stdout.write("".join(masked))
PYEOF
)

# Achata quebras de linha para permitir casar padrões que atravessam continuações
# de linha (ex.: "git \\\n  push --force").
FLAT=$(printf '%s' "$SCAN" | tr '\n' ' ')

block() {
  echo "BLOCKED: '$COMMAND' — $1. O usuário não autorizou este comando; peça confirmação explícita antes de prosseguir." >&2
  exit 2
}

# Flags globais conhecidas do git que podem aparecer ANTES do subcomando
# (ex.: "git -C /opt/VoipIA branch -D x") — sem isso, um simples "-C <path>"
# bypassa a checagem por quebrar a adjacência "git <subcomando>".
GITPFX='git(\s+(-C\s+\S+|-c\s+\S+|--git-dir=\S+|--work-tree=\S+|-P|--no-pager|--no-replace-objects))*'

# --- Git destrutivo -----------------------------------------------------
echo "$FLAT" | grep -qE "$GITPFX\s+push\b.*(--force\b|-f\b)" && \
  block "force-push reescreve o histórico remoto e pode sobrescrever trabalho de outra pessoa"

echo "$FLAT" | grep -qE "$GITPFX\s+reset\s+--hard\b" && \
  block "reset --hard descarta permanentemente alterações locais não commitadas"

echo "$FLAT" | grep -qE "$GITPFX\s+clean\s+.*-[a-zA-Z]*f" && \
  block "git clean -f remove arquivos não versionados sem chance de recuperação"

echo "$FLAT" | grep -qE "$GITPFX\s+branch\s+.*-D\b" && \
  block "branch -D força a remoção de uma branch mesmo com commits não mesclados"

echo "$FLAT" | grep -qE "$GITPFX\s+(checkout|restore)\s+(--\s+)?\.(\s|\$)" && \
  block "descarta TODAS as alterações não commitadas no working tree"

# --- Regras específicas de produção do VoipIA ------------------------
# "Nunca fazer docker compose down sem docker compose up imediato" (CLAUDE.md).
if echo "$FLAT" | grep -qE '(^|[;&|]|\s)docker\s+compose\s+down\b'; then
  echo "$FLAT" | grep -qE '\bdocker\s+compose\s+up\b' || \
    block "docker compose down sem 'up' na mesma chamada derruba o Caddy e tira o sistema do ar (regra do CLAUDE.md)"
fi

# "Nunca remover o symlink /opt/VoipIA/.env" e nunca apagar env/.env sem backup.
echo "$FLAT" | grep -qE '(^|[;&|]|\s)(rm|unlink)\b.*(/opt/VoipIA/\.env\b|/opt/VoipIA/env/\.env\b|/opt/VoipIA/env\b)' && \
  block "remove o .env real, o symlink ou o diretório env/ — regra inegociável do CLAUDE.md (sempre fazer backup antes e nunca remover o symlink)"

exit 0
