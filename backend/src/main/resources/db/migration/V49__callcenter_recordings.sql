-- V49 — Gravação, retenção e conformidade do Call Center (Fase 3). cc_recordings rastreia cada
-- gravação MixMonitor feita pelo dialplan em `_5XXX` (faixa de filas); recording_enabled/
-- consent_message_path em cc_queues são configuráveis por fila (aviso de gravação, LGPD).
-- Sem FK para uma interação formal ainda (cc_interactions chega na Fase 4) — o vínculo hoje é só
-- por queue_id/extension/channel_uniqueid, prontos para ganhar interaction_id depois.

ALTER TABLE cc_queues
    ADD COLUMN recording_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN consent_message_path VARCHAR(255);

CREATE TABLE cc_recordings (
    id                 BIGSERIAL PRIMARY KEY,
    queue_id           BIGINT REFERENCES cc_queues(id),
    queue_extension    VARCHAR(10) NOT NULL,
    channel_uniqueid   VARCHAR(64) NOT NULL UNIQUE,
    file_path          VARCHAR(255) NOT NULL,
    business_unit_id   INTEGER REFERENCES business_units(id),
    consent_played     BOOLEAN NOT NULL DEFAULT false,
    started_at         TIMESTAMP NOT NULL,
    ended_at           TIMESTAMP,
    duration_seconds   INTEGER,
    created_at         TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_cc_recordings_queue_id ON cc_recordings(queue_id);
CREATE INDEX idx_cc_recordings_started_at ON cc_recordings(started_at);

-- Config de retenção — linha única (mesmo padrão de FinanceiroCostAlertConfig)
CREATE TABLE cc_recording_retention_config (
    id                       VARCHAR(20) PRIMARY KEY DEFAULT 'default',
    retention_days           INTEGER NOT NULL DEFAULT 1800, -- 60 meses
    last_purge_at            TIMESTAMP,
    last_purge_deleted_count INTEGER,
    updated_by               VARCHAR(100),
    updated_at               TIMESTAMP NOT NULL DEFAULT now()
);

-- Config de alerta de disco — linha única, granularidade diária (não mensal)
CREATE TABLE cc_recording_disk_alert_config (
    id                 VARCHAR(20) PRIMARY KEY DEFAULT 'default',
    threshold_percent  INTEGER NOT NULL DEFAULT 85,
    enabled            BOOLEAN NOT NULL DEFAULT true,
    last_notified_date DATE,
    updated_by         VARCHAR(100),
    updated_at         TIMESTAMP NOT NULL DEFAULT now()
);
