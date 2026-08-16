#!/usr/bin/env bash
# pre-commit-media-guard.sh — Fase 20 do plano Call Center Parte III.
#
# /opt/VoipIA/media/ (gravação de chamada, transcript de chat, biblioteca de anúncios, upload
# de análise sob demanda) vive sob a raiz do repositório desde a Fase 20 — é dado de cliente/PII,
# nunca pode ser comitado. O .gitignore (media/*) já cobre o caso comum, mas este hook é a segunda
# camada: recusa o commit se algum caminho sob media/ (fora do próprio .gitignore) entrar staged,
# o que aconteceria com `git add -f` OU com `git mv <arquivo-já-rastreado> media/...`.
#
# Achado real de segurança (ecc:security-reviewer, HIGH): a primeira versão deste hook usava
# --diff-filter=ACM (Added/Copied/Modified), que EXCLUI renomeação (status R) — um `git mv` de um
# arquivo já rastreado para dentro de media/ passava pelo hook sem ser bloqueado, sem precisar de
# `-f` nem de nenhuma flag especial. Corrigido para ACMR; a exclusão fica implícita (só D de
# deleção não aparece em --name-only de qualquer forma).
#
# Instalação (não versionada pelo git em si — .git/hooks não é rastreado):
#   ln -sf ../../scripts/git-hooks/pre-commit-media-guard.sh .git/hooks/pre-commit
set -euo pipefail

staged_media=$(git diff --cached --name-only --diff-filter=ACMR | grep -E '^media/' | grep -v '^media/\.gitignore$' || true)

if [[ -n "$staged_media" ]]; then
    echo "ERRO: commit bloqueado — arquivo(s) sob media/ estão staged (dado de cliente/PII):" >&2
    echo "$staged_media" | sed 's/^/  /' >&2
    echo "Se isso foi 'git add -f' por engano, desfaça com: git restore --staged <arquivo>" >&2
    exit 1
fi

exit 0
