#!/bin/bash
# Migra os arquivos físicos de mídia do Call Center/Insights para o padrão único sob a raiz do
# repositório: /opt/VoipIA/media/{gravacao,chat,anuncios,sobdemanda} — Fase 20 do plano Call
# Center Parte III. Generaliza o script da Fase 11 (que só cobria voz/chat de
# /opt/telecom/gravacao → /opt/gravacoes/audio) para qualquer par origem/destino, porque agora há
# 3 migrações reais (gravação de voz, transcript de chat, upload de análise sob demanda) mais um
# diretório novo sem conteúdo prévio (anúncios do flow builder, Fase 5c).
#
# media/ é git-ignorado (ver .gitignore) — nunca fica dentro do controle de versão, mesmo estando
# sob a raiz do repo.
#
# A leitura via CallCenterRecordingService.resolveAudioFile não depende do prefixo persistido em
# cc_recordings.file_path (usa só o nome-base + o diretório yyyy/MM/dd reconstruído a partir de
# started_at), então mover os arquivos é suficiente — a migration V63 só corrige o dado no banco
# para não mentir sobre a localização física.
#
# Uso: sudo bash scripts/migrar-gravacoes.sh
set -euo pipefail

# Achado real do deploy da Fase 11 nesta VPS (2026-08-08): o container backend passou a rodar
# como usuário não-root (uid 1501, grupo voipia-app/gid 1500 — de-rootização em andamento,
# ver backend/Dockerfile) e não conseguia escrever num diretório novo criado por bind mount
# (root:root 755 por padrão do Docker). O Asterisk continua root e grava normalmente, mas sem o
# grupo compartilhado o backend não conseguia ler/expurgar o próprio arquivo gravado por ele —
# nem gravar o transcript de chat. setgid (2770) garante que todo arquivo novo criado dentro
# herde o grupo voipia-app, independente do processo que o criou (root ou uid 1501) — mesmo
# padrão já usado em /opt/VoipIA/env. Roda incondicionalmente, mesmo sem nada pra migrar.
ensure_shared_group_permission() {
  local dir="$1"
  mkdir -p "$dir"
  if getent group voipia-app >/dev/null 2>&1; then
    chown root:voipia-app "$dir"
    chmod 2770 "$dir"
  else
    echo "AVISO: grupo 'voipia-app' não existe neste host — pulei o ajuste de permissão de $dir." >&2
    echo "Confirme manualmente que quem grava e quem lê/apaga/exporta têm acesso." >&2
  fi
}

# migrate_dir <origem> <destino> <extensao_glob>
# rsync + verificação de contagem antes de remover a origem. Extensão vazia migra tudo (usado
# para o transcript de chat, que grava .json e .txt lado a lado).
migrate_dir() {
  local origem="$1" destino="$2" extensao="${3:-}"
  ensure_shared_group_permission "$destino"

  if [ ! -d "$origem" ]; then
    echo "Origem '$origem' não existe — nada a migrar para $destino (ambiente novo ou já migrado)."
    return 0
  fi

  local find_expr=()
  if [ -n "$extensao" ]; then
    find_expr=(-name "$extensao")
  fi

  echo "Contando arquivos em '$origem'..."
  local contagem_origem
  contagem_origem=$(find "$origem" -type f "${find_expr[@]}" | wc -l)
  echo "Encontrados: $contagem_origem"

  if [ "$contagem_origem" -eq 0 ]; then
    echo "Origem sem arquivos — nada a migrar."
    return 0
  fi

  echo "Copiando com rsync (preservando estrutura de diretórios)..."
  rsync -a "$origem"/ "$destino"/

  # Achado real desta execução (Fase 20): `rsync -a origem/ destino/` sincroniza os atributos do
  # PRÓPRIO diretório de destino contra a origem (permissão/dono), sobrescrevendo o setgid 2770
  # aplicado acima antes de qualquer arquivo existir para copiar — reaplica incondicionalmente
  # depois do rsync, no diretório e em tudo que ele trouxe (arquivos/subpastas copiados não
  # herdam setgid retroativamente, só o que for criado dali em diante).
  ensure_shared_group_permission "$destino"
  if getent group voipia-app >/dev/null 2>&1; then
    chown -R root:voipia-app "$destino"
    find "$destino" -type d -exec chmod 2770 {} +
    find "$destino" -type f -exec chmod 660 {} +
  fi

  local contagem_destino
  contagem_destino=$(find "$destino" -type f "${find_expr[@]}" | wc -l)
  echo "Arquivos no destino após cópia: $contagem_destino"

  if [ "$contagem_destino" -lt "$contagem_origem" ]; then
    echo "ERRO: destino '$destino' tem menos arquivos que a origem ($contagem_destino < $contagem_origem)." >&2
    echo "Nada foi removido da origem. Investigue antes de prosseguir." >&2
    exit 1
  fi

  echo "Contagem confere. Removendo arquivos da origem '$origem'..."
  find "$origem" -type f "${find_expr[@]}" -delete
  find "$origem" -type d -empty -delete 2>/dev/null || true
  echo "Migração de '$origem' para '$destino' concluída: $contagem_destino arquivo(s)."
}

echo "=== Gravação de voz (/opt/gravacoes/audio → /opt/VoipIA/media/gravacao) ==="
migrate_dir "/opt/gravacoes/audio" "/opt/VoipIA/media/gravacao" "*.wav"

echo
echo "=== Transcript de chat (/opt/gravacoes/chat → /opt/VoipIA/media/chat) ==="
migrate_dir "/opt/gravacoes/chat" "/opt/VoipIA/media/chat"

echo
echo "=== Upload de análise sob demanda (/opt/audio_upload → /opt/VoipIA/media/sobdemanda) ==="
migrate_dir "/opt/audio_upload" "/opt/VoipIA/media/sobdemanda"

echo
echo "=== Biblioteca de anúncios do flow builder (novo, sem origem — Fase 5c) ==="
ensure_shared_group_permission "/opt/VoipIA/media/anuncios"

echo
echo "Próximo passo: aplicar a migration V63 (docker compose up -d --build backend) para"
echo "corrigir cc_recordings.file_path/cc_chat_sessions.transcript_path, e confirmar em"
echo "flyway_schema_history."
