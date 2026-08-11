#!/bin/bash
# Migra os arquivos físicos de gravação de voz do caminho antigo (/opt/telecom/gravacao) para o
# novo padrão (/opt/gravacoes/audio) e garante a permissão correta dos dois diretórios novos de
# gravação (audio + chat) — Fase 11 do plano do módulo Call Center.
#
# A leitura via CallCenterRecordingService.resolveAudioFile não depende do prefixo persistido em
# cc_recordings.file_path (usa só o nome-base + o diretório yyyy/MM/dd reconstruído a partir de
# started_at), então mover os arquivos é suficiente — a migration V60 só corrige o dado no banco
# para não mentir sobre a localização física.
#
# Uso: sudo bash scripts/migrar-gravacoes.sh
set -euo pipefail

ORIGEM_AUDIO="/opt/telecom/gravacao"
DESTINO_AUDIO="/opt/gravacoes/audio"
DESTINO_CHAT="/opt/gravacoes/chat"

# Achado real do deploy da Fase 11 nesta VPS (2026-08-08): o container backend passou a rodar
# como usuário não-root (uid 1501, grupo asteriskia-app/gid 1500 — de-rootização em andamento,
# ver backend/Dockerfile) e não conseguia escrever num diretório novo criado por bind mount
# (root:root 755 por padrão do Docker). O Asterisk continua root e grava normalmente, mas sem o
# grupo compartilhado o backend não conseguia ler/expurgar o próprio arquivo gravado por ele —
# nem gravar o transcript de chat. setgid (2770) garante que todo arquivo novo criado dentro
# herde o grupo asteriskia-app, independente do processo que o criou (root ou uid 1501) — mesmo
# padrão já usado em /opt/AsteriskIA/env. Roda incondicionalmente, mesmo sem nada pra migrar,
# porque também vale para /opt/gravacoes/chat, que nunca teve conteúdo antigo.
ensure_shared_group_permission() {
  local dir="$1"
  mkdir -p "$dir"
  if getent group asteriskia-app >/dev/null 2>&1; then
    chown root:asteriskia-app "$dir"
    chmod 2770 "$dir"
  else
    echo "AVISO: grupo 'asteriskia-app' não existe neste host — pulei o ajuste de permissão de $dir." >&2
    echo "Confirme manualmente que o Asterisk (grava) e o backend (lê/apaga/exporta) têm acesso." >&2
  fi
}

ensure_shared_group_permission "$DESTINO_AUDIO"
ensure_shared_group_permission "$DESTINO_CHAT"

if [ ! -d "$ORIGEM_AUDIO" ]; then
  echo "Origem '$ORIGEM_AUDIO' não existe — nada a migrar (ambiente novo ou já migrado)."
  exit 0
fi

echo "Contando arquivos .wav na origem..."
CONTAGEM_ORIGEM=$(find "$ORIGEM_AUDIO" -type f -name '*.wav' | wc -l)
echo "Encontrados: $CONTAGEM_ORIGEM"

if [ "$CONTAGEM_ORIGEM" -eq 0 ]; then
  echo "Origem sem arquivos .wav — nada a migrar."
  exit 0
fi

echo "Copiando com rsync (preservando estrutura YYYY/MM/DD)..."
rsync -a "$ORIGEM_AUDIO"/ "$DESTINO_AUDIO"/

CONTAGEM_DESTINO=$(find "$DESTINO_AUDIO" -type f -name '*.wav' | wc -l)
echo "Arquivos no destino após cópia: $CONTAGEM_DESTINO"

if [ "$CONTAGEM_DESTINO" -lt "$CONTAGEM_ORIGEM" ]; then
  echo "ERRO: destino tem menos arquivos que a origem ($CONTAGEM_DESTINO < $CONTAGEM_ORIGEM)." >&2
  echo "Nada foi removido da origem. Investigue antes de prosseguir." >&2
  exit 1
fi

echo "Contagem confere. Removendo arquivos da origem..."
find "$ORIGEM_AUDIO" -type f -name '*.wav' -delete
find "$ORIGEM_AUDIO" -type d -empty -delete 2>/dev/null || true

echo "Migração concluída: $CONTAGEM_DESTINO arquivo(s) em $DESTINO_AUDIO."
echo "Próximo passo: aplicar a migration V60 (docker compose up -d --build backend) para"
echo "corrigir cc_recordings.file_path, e confirmar em flyway_schema_history."
