-- V20: escopa as mensagens/configurações da URA por URA (boas-vindas,
-- informativa, encerramento, agressividade do VAD deixam de ser globais).
--
-- A chave 'key' deixa de ser PK sozinha (cada URA terá sua própria linha
-- 'boas_vindas', 'encerramento' etc.) — passa a existir um id substituto e
-- a unicidade vira (ura_id, key).

ALTER TABLE ura_settings ADD COLUMN id BIGSERIAL;
ALTER TABLE ura_settings ADD COLUMN ura_id INTEGER;
UPDATE ura_settings SET ura_id = 1;
ALTER TABLE ura_settings ALTER COLUMN ura_id SET NOT NULL;

ALTER TABLE ura_settings DROP CONSTRAINT ura_settings_pkey;
ALTER TABLE ura_settings ADD PRIMARY KEY (id);
ALTER TABLE ura_settings ADD CONSTRAINT uq_ura_settings_ura_key UNIQUE (ura_id, key);
ALTER TABLE ura_settings ADD CONSTRAINT fk_ura_settings_ura FOREIGN KEY (ura_id) REFERENCES uras(id);

CREATE INDEX idx_ura_settings_ura_id ON ura_settings(ura_id);
