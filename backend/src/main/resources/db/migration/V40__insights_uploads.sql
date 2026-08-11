-- V40: portal do supervisor — upload em lote de áudios para transcrição/análise ad-hoc
-- (Fase 3 da evolução do módulo Insights para Quality Management). Reusa call_audio_files
-- (mesmo pipeline de STT/análise/avaliação do fluxo Verint) — a coluna `source` distingue
-- a origem, sem duplicar nenhuma tabela. Uploads não têm XML da Verint, por isso xml_path
-- deixa de ser obrigatório.

ALTER TABLE call_audio_files
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'verint',
    ADD COLUMN uploaded_by VARCHAR(100),
    ADD COLUMN upload_batch_id UUID,
    ALTER COLUMN xml_path DROP NOT NULL;

COMMENT ON COLUMN call_audio_files.source IS 'verint (gravação descoberta em /opt/audio pelo watcher) ou upload (enviada pelo portal do supervisor em /opt/audio_upload) — dashboards/listagens do fluxo Verint são sempre restritos a source=verint';
COMMENT ON COLUMN call_audio_files.uploaded_by IS 'Username do supervisor que enviou o arquivo — null para source=verint';
COMMENT ON COLUMN call_audio_files.upload_batch_id IS 'Lote de upload a que este arquivo pertence — null para source=verint';

CREATE INDEX idx_call_audio_files_source ON call_audio_files(source);
CREATE INDEX idx_call_audio_files_upload_batch ON call_audio_files(upload_batch_id);
CREATE INDEX idx_call_audio_files_uploaded_by ON call_audio_files(uploaded_by) WHERE uploaded_by IS NOT NULL;

-- ─── upload_batches — um lote de até 100 arquivos enviados de uma vez pelo supervisor ───
CREATE TABLE upload_batches (
    id          UUID PRIMARY KEY,
    uploaded_by VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    file_count  INT NOT NULL,
    notes       TEXT
);

COMMENT ON TABLE upload_batches IS 'Lote de upload do portal do supervisor — cada arquivo do lote vira uma linha em call_audio_files (source=upload, upload_batch_id=id deste lote)';

CREATE INDEX idx_upload_batches_uploaded_by ON upload_batches(uploaded_by, created_at DESC);
