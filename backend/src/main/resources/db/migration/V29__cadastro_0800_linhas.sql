-- V29: novos cadastros "0800" e "Linhas" no bloco CADASTROS do menu, com
-- vínculo opcional N:N a Unidade de Negócio (BU) — mesmo padrão de escopo por
-- BU já usado em clients/operations (V25) — e novos resource_keys RBAC
-- ("telecom.0800", "telecom.linhas") liberados para os dois grupos seed
-- (Administradores e Usuários), pois são cadastros operacionais com escrita.

CREATE TABLE numeros_0800 (
    id          SERIAL PRIMARY KEY,
    operadora   VARCHAR(200) NOT NULL,
    numero      VARCHAR(40)  NOT NULL,
    client_id   INTEGER REFERENCES clients(id) ON DELETE SET NULL,
    observacao  VARCHAR(500),
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE numeros_0800 IS 'Números 0800 cadastrados (bloco Cadastros)';

CREATE TABLE numero_0800_regenerados (
    id                SERIAL PRIMARY KEY,
    numero_0800_id    INTEGER NOT NULL REFERENCES numeros_0800(id) ON DELETE CASCADE,
    ordem             INTEGER NOT NULL,
    numero_regenerado VARCHAR(40),
    vdn               VARCHAR(40),
    vetor             VARCHAR(100),
    operadora         VARCHAR(100),
    -- DEFERRABLE: reordenar os grupos (remover um do meio, renumerar os demais)
    -- gera UPDATEs e DELETEs na mesma transação; o Hibernate emite os UPDATEs
    -- antes dos DELETEs no flush, então uma checagem imediata da constraint
    -- colidiria transitoriamente com a linha ainda não removida. Adiar a
    -- checagem para o commit evita o falso-positivo sem exigir lógica extra
    -- no backend.
    CONSTRAINT uk_numero_0800_regenerados_ordem UNIQUE (numero_0800_id, ordem) DEFERRABLE INITIALLY DEFERRED
);
COMMENT ON TABLE numero_0800_regenerados IS 'Grupos de regeneração (até 5) de um número 0800';

CREATE TABLE numeros_0800_business_units (
    numero_0800_id    INTEGER NOT NULL REFERENCES numeros_0800(id) ON DELETE CASCADE,
    business_unit_id  INTEGER NOT NULL REFERENCES business_units(id) ON DELETE CASCADE,
    PRIMARY KEY (numero_0800_id, business_unit_id)
);
CREATE INDEX idx_numeros_0800_business_units_bu ON numeros_0800_business_units(business_unit_id);

CREATE TABLE linhas (
    id             SERIAL PRIMARY KEY,
    operadora      VARCHAR(200) NOT NULL,
    operation_id   INTEGER REFERENCES operations(id) ON DELETE SET NULL,
    chave          VARCHAR(200),
    ip_operadora   VARCHAR(64),
    ip_autoglass   VARCHAR(64),
    observacao     VARCHAR(500),
    is_active      BOOLEAN DEFAULT TRUE,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE linhas IS 'Linhas de operadora cadastradas (bloco Cadastros)';

CREATE TABLE linhas_business_units (
    linha_id          INTEGER NOT NULL REFERENCES linhas(id) ON DELETE CASCADE,
    business_unit_id  INTEGER NOT NULL REFERENCES business_units(id) ON DELETE CASCADE,
    PRIMARY KEY (linha_id, business_unit_id)
);
CREATE INDEX idx_linhas_business_units_bu ON linhas_business_units(business_unit_id);

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write) VALUES
    (1, 'telecom.0800',   TRUE, TRUE),
    (2, 'telecom.0800',   TRUE, TRUE),
    (1, 'telecom.linhas', TRUE, TRUE),
    (2, 'telecom.linhas', TRUE, TRUE);
