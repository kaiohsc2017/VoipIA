-- V25: DATACENTER — cadastro central de números (DDR/0800/WhatsApp) que
-- alimenta automaticamente Clientes (Unidade de Negócio) e o Módulo
-- Conectividade (guias 0800/DDR), com template padrão de agendamento por
-- Segmento.
--
-- Aditiva e não-destrutiva: number_tests existentes continuam funcionando
-- sem nenhuma alteração (phone_number_id é nullable — só testes criados a
-- partir do DATACENTER a partir de agora ganham o vínculo).

CREATE TABLE phone_numbers (
    id                BIGSERIAL PRIMARY KEY,
    phone_number      VARCHAR(20)  NOT NULL,
    number_type       VARCHAR(20)  NOT NULL,   -- DDR | ZERO_OITO_ZERO_ZERO | WHATSAPP
    business_unit_id  INTEGER      NOT NULL REFERENCES business_units(id),
    client_id         INTEGER      NOT NULL REFERENCES clients(id),
    operation_id      INTEGER      REFERENCES operations(id),   -- NULL = pendente
    segment_id        INTEGER      REFERENCES segments(id),     -- NULL = pendente
    observation       VARCHAR(300),
    is_active         BOOLEAN      DEFAULT TRUE,
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_phone_numbers_number_type UNIQUE (phone_number, number_type)
);

COMMENT ON TABLE phone_numbers IS 'DATACENTER — inventário central de números (DDR/0800/WhatsApp) por BU/Cliente';
COMMENT ON COLUMN phone_numbers.operation_id IS 'NULL enquanto o cadastro não é completado no Cliente — número fica pendente';
COMMENT ON COLUMN phone_numbers.segment_id IS 'NULL enquanto o cadastro não é completado no Cliente — número fica pendente';

-- Liga um number_tests à sua origem no DATACENTER. NULL = teste criado
-- manualmente na tela de Conectividade (fluxo que já existia antes desta
-- feature, permanece intacto).
ALTER TABLE number_tests ADD COLUMN phone_number_id BIGINT UNIQUE REFERENCES phone_numbers(id) ON DELETE SET NULL;

COMMENT ON COLUMN number_tests.phone_number_id IS 'Origem no DATACENTER — NULL para testes criados manualmente';

-- Template padrão de agendamento por Segmento — todo NumberTest novo criado
-- a partir do DATACENTER para esse segmento nasce com esses valores (em vez
-- de um default genérico), editável individualmente depois.
ALTER TABLE segments ADD COLUMN default_start_time TIME;
ALTER TABLE segments ADD COLUMN default_interval_minutes INTEGER;
ALTER TABLE segments ADD COLUMN default_quantity INTEGER;

COMMENT ON COLUMN segments.default_start_time IS 'Template padrão de agendamento aplicado a novos NumberTests deste segmento (DATACENTER)';

-- Novo resource_key "telecom.datacenter" — mesmo padrão de telecom.masterdata
-- e telecom.modulo2 (ambos rw para o grupo Usuários, ver V22).
INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write) VALUES
    (1, 'telecom.datacenter', TRUE, TRUE),
    (2, 'telecom.datacenter', TRUE, TRUE);
