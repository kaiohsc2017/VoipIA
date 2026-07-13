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

# Remove o CORPO de heredocs (ex.: mensagens de commit via "cat <<'EOF' ... EOF")
# antes de checar padrões perigosos — senão texto de prosa dentro da mensagem
# (ex.: alguém escrevendo "docker compose down" na descrição do commit) dispara
# um falso positivo. Só o comando real é avaliado, não o conteúdo que ele carrega.
SCAN=$(echo "$COMMAND" | awk '
  BEGIN { skip = 0 }
  {
    if (skip) {
      line = $0
      sub(/^[ \t]+/, "", line)
      if (line == delim) { skip = 0 }
      next
    }
    if (match($0, /<<-?[ \t]*['\''"]?[A-Za-z_][A-Za-z0-9_]*['\''"]?/)) {
      tok = substr($0, RSTART, RLENGTH)
      gsub(/<<-?[ \t]*/, "", tok)
      gsub(/['\''"]/, "", tok)
      delim = tok
      skip = 1
    }
    print $0
  }
')

block() {
  echo "BLOCKED: '$COMMAND' — $1. O usuário não autorizou este comando; peça confirmação explícita antes de prosseguir." >&2
  exit 2
}

# --- Git destrutivo -----------------------------------------------------
echo "$SCAN" | grep -qE '(^|[;&|]|\s)git\s+push\b.*(--force\b|-f\b)' && \
  block "force-push reescreve o histórico remoto e pode sobrescrever trabalho de outra pessoa"

echo "$SCAN" | grep -qE '(^|[;&|]|\s)git\s+reset\s+--hard\b' && \
  block "reset --hard descarta permanentemente alterações locais não commitadas"

echo "$SCAN" | grep -qE '(^|[;&|]|\s)git\s+clean\s+.*-[a-zA-Z]*f' && \
  block "git clean -f remove arquivos não versionados sem chance de recuperação"

echo "$SCAN" | grep -qE '(^|[;&|]|\s)git\s+branch\s+-D\b' && \
  block "branch -D força a remoção de uma branch mesmo com commits não mesclados"

echo "$SCAN" | grep -qE '(^|[;&|]|\s)git\s+(checkout|restore)\s+\.\s*($|[;&|])' && \
  block "descarta TODAS as alterações não commitadas no working tree"

# --- Regras específicas de produção do AsteriskIA ------------------------
# "Nunca fazer docker compose down sem docker compose up imediato" (CLAUDE.md).
if echo "$SCAN" | grep -qE '(^|[;&|]|\s)docker\s+compose\s+down\b'; then
  echo "$SCAN" | grep -qE '\bdocker\s+compose\s+up\b' || \
    block "docker compose down sem 'up' na mesma chamada derruba o Caddy e tira o sistema do ar (regra do CLAUDE.md)"
fi

# "Nunca remover o symlink /opt/AsteriskIA/.env" e nunca apagar env/.env sem backup.
echo "$SCAN" | grep -qE '(^|[;&|]|\s)(rm|unlink)\b.*(/opt/AsteriskIA/\.env\b|/opt/AsteriskIA/env/\.env\b|/opt/AsteriskIA/env\b)' && \
  block "remove o .env real, o symlink ou o diretório env/ — regra inegociável do CLAUDE.md (sempre fazer backup antes e nunca remover o symlink)"

exit 0
