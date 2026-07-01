-- V18: Múltiplas URAs configuráveis — cada uma com ramal, perguntas e
-- integração com Jira próprios (Fase 0 da generalização do Módulo 1).

CREATE TABLE uras (
    id                        SERIAL PRIMARY KEY,
    name                      VARCHAR(150) NOT NULL,
    extension                 VARCHAR(20)  NOT NULL UNIQUE,
    active                    BOOLEAN      NOT NULL DEFAULT TRUE,
    jira_integration_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  uras                           IS 'URAs configuráveis — cada uma com ramal, perguntas e integração próprios';
COMMENT ON COLUMN uras.extension                 IS 'Ramal que aciona esta URA. 1000 é a URA legada; novas URAs usam a faixa reservada 2000-2999';
COMMENT ON COLUMN uras.jira_integration_enabled  IS 'Se false, a chamada é registrada normalmente mas nenhum chamado é aberto no Jira';

-- A URA existente (service desk) vira a primeira URA do sistema — sem
-- caminho de código duplicado, ela passa a ser só "a URA de id=1".
INSERT INTO uras (id, name, extension, active, jira_integration_enabled) VALUES
(1, 'Service Desk (Jira)', '1000', TRUE, TRUE);

SELECT setval('uras_id_seq', (SELECT MAX(id) FROM uras));
