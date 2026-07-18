-- V36: adiciona started_at em call_audio_files — distingue "descoberto" (ingested_at,
-- já existente) de "processamento começou de fato" (started_at, novo), necessário para
-- a aba "Processamento" da tela Insights mostrar data de início real, não a data de
-- descoberta do arquivo. status/error_msg/processed_at já existiam desde a V35 mas
-- nunca eram usados com valores intermediários (pending/processing/error) — esta
-- entrega instrumenta o pipeline (backend + serviço Python) para popular esses campos
-- em tempo real, em vez de só gravar a linha inteira no final do processamento.

ALTER TABLE call_audio_files ADD COLUMN started_at TIMESTAMP;

COMMENT ON COLUMN call_audio_files.started_at IS 'Quando o processamento desta chamada começou de fato (retirada da fila pelo watcher) — distinto de ingested_at (quando foi descoberta em /opt/audio) e processed_at (quando terminou, com sucesso ou erro)';
