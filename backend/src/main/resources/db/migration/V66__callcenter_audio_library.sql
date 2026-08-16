-- Fase 5c (ampliada) do plano omnicanal Parte III — biblioteca de áudios do Flow Builder.
-- Upload sempre transcodificado para PCM 8kHz/16-bit mono (ffmpeg) — o arquivo original nunca é
-- mantido nem servido ao Asterisk. Destino físico: /opt/VoipIA/media/anuncios (padrão da
-- Fase 20; revoga o /opt/gravacoes/flow do rascunho original desta fase).

CREATE TABLE cc_audio_files (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(150) NOT NULL,
    file_name         VARCHAR(200) NOT NULL UNIQUE,
    format            VARCHAR(20) NOT NULL DEFAULT 'wav',
    duration_seconds  INT,
    business_unit_id  INTEGER REFERENCES business_units(id),
    uploaded_by       VARCHAR(100) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON COLUMN cc_audio_files.file_name IS 'Nome físico em /opt/VoipIA/media/anuncios (sem extensão) — Asterisk resolve via sound:asteriskia/<file_name>';
