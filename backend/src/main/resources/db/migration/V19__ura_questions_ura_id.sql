-- V19: escopa as perguntas da URA por URA (FK ura_id).
-- Backfill: todas as perguntas existentes pertencem à URA legada (id=1).

ALTER TABLE ura_questions ADD COLUMN ura_id INTEGER;
UPDATE ura_questions SET ura_id = 1;
ALTER TABLE ura_questions ALTER COLUMN ura_id SET NOT NULL;
ALTER TABLE ura_questions ADD CONSTRAINT fk_ura_questions_ura FOREIGN KEY (ura_id) REFERENCES uras(id);

CREATE INDEX idx_ura_questions_ura_id ON ura_questions(ura_id);
