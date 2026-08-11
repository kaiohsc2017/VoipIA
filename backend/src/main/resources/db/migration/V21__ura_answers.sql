-- V21: tabela de respostas por pergunta (sem DDL dinâmica ao criar/editar
-- perguntas) + FK ura_id em call_records para saber de qual URA cada
-- chamada veio.

CREATE TABLE ura_answers (
    id               BIGSERIAL PRIMARY KEY,
    call_record_id   BIGINT    NOT NULL REFERENCES call_records(id) ON DELETE CASCADE,
    ura_question_id  INTEGER   NOT NULL REFERENCES ura_questions(id),
    value            TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ura_answers IS 'Uma linha por resposta de pergunta da URA — evita ALTER TABLE a cada pergunta criada/editada';

CREATE INDEX idx_ura_answers_call_record ON ura_answers(call_record_id);
CREATE INDEX idx_ura_answers_question    ON ura_answers(ura_question_id);

ALTER TABLE call_records ADD COLUMN ura_id INTEGER;
UPDATE call_records SET ura_id = 1;
ALTER TABLE call_records ALTER COLUMN ura_id SET NOT NULL;
ALTER TABLE call_records ADD CONSTRAINT fk_call_records_ura FOREIGN KEY (ura_id) REFERENCES uras(id);

CREATE INDEX idx_call_records_ura_id ON call_records(ura_id);
