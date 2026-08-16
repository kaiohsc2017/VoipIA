-- =============================================================
-- V1__init_schema.sql
-- Schema inicial do VoipIA.
-- Executado automaticamente pelo PostgreSQL na primeira
-- inicialização do container (via docker-entrypoint-initdb.d).
-- =============================================================

-- Extensão para UUID
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =============================================================
-- MÓDULO 1 — Registro de Chamadas no Jira
-- =============================================================

/**
 * ura_questions
 * Perguntas configuráveis da URA.
 * Cada pergunta é lida via TTS e mapeada a um campo do Jira.
 */
CREATE TABLE ura_questions (
    id              SERIAL PRIMARY KEY,
    question_order  INTEGER NOT NULL,
    question_text   VARCHAR(1000) NOT NULL,
    jira_field_key  VARCHAR(100) NOT NULL,
    expected_values VARCHAR(500),           -- Valores válidos separados por vírgula (ex: "Baixa,Média,Alta")
    is_active       BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ura_questions IS 'Perguntas da URA mapeadas aos campos de abertura de chamado no Jira';
COMMENT ON COLUMN ura_questions.expected_values IS 'Valores válidos separados por vírgula. Null = resposta livre';

/**
 * call_records
 * Registro de cada chamada recebida na URA do Módulo 1.
 */
CREATE TABLE call_records (
    id                  BIGSERIAL PRIMARY KEY,
    call_uuid           UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    call_date           TIMESTAMP NOT NULL,
    call_duration_secs  INTEGER NOT NULL DEFAULT 0,
    caller_number       VARCHAR(20) NOT NULL,
    client_name         VARCHAR(200),
    transcription       TEXT,
    jira_issue_key      VARCHAR(30),
    jira_issue_status   VARCHAR(50) DEFAULT 'Aberto',
    audio_file_path     VARCHAR(500),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE call_records IS 'Chamadas recebidas na URA com transcrição e referência ao chamado Jira';
COMMENT ON COLUMN call_records.call_uuid IS 'UUID único da chamada gerado pelo Asterisk (UNIQUEID)';

CREATE INDEX idx_call_records_date ON call_records(call_date);
CREATE INDEX idx_call_records_jira ON call_records(jira_issue_key);
CREATE INDEX idx_call_records_caller ON call_records(caller_number);

-- =============================================================
-- MÓDULO 2 — Teste de Conectividade de Números
-- =============================================================

/**
 * business_units
 * Cadastro de Business Units (BU).
 */
CREATE TABLE business_units (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_business_units_name UNIQUE (name)
);

COMMENT ON TABLE business_units IS 'Unidades de negócio (BU) cadastradas para agrupamento de testes';

/**
 * segments
 * Cadastro de segmentos de negócio.
 */
CREATE TABLE segments (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_segments_name UNIQUE (name)
);

COMMENT ON TABLE segments IS 'Segmentos de negócio para categorização dos testes';

/**
 * clients
 * Cadastro de clientes.
 */
CREATE TABLE clients (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    document    VARCHAR(20),                    -- CNPJ ou CPF
    description VARCHAR(300),
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_clients_name UNIQUE (name)
);

COMMENT ON TABLE clients IS 'Clientes cadastrados no sistema';

/**
 * operations
 * Cadastro de operações.
 */
CREATE TABLE operations (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(300),
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_operations_name UNIQUE (name)
);

COMMENT ON TABLE operations IS 'Operações cadastradas. Vinculadas a clientes pela tabela client_operations';

/**
 * client_operations
 * Relacionamento N:N entre clientes e operações.
 * Regra: ao selecionar um cliente no frontend, apenas
 * as operações vinculadas aqui serão exibidas.
 */
CREATE TABLE client_operations (
    client_id       INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    operation_id    INTEGER NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    PRIMARY KEY (client_id, operation_id)
);

COMMENT ON TABLE client_operations IS 'Vínculo N:N entre clientes e operações permitidas';

/**
 * number_tests
 * Cadastro de números a testar com configuração de agendamento.
 */
CREATE TABLE number_tests (
    id                  BIGSERIAL PRIMARY KEY,
    phone_number        VARCHAR(20) NOT NULL,
    business_unit_id    INTEGER NOT NULL REFERENCES business_units(id),
    client_id           INTEGER NOT NULL REFERENCES clients(id),
    operation_id        INTEGER NOT NULL REFERENCES operations(id),
    segment_id          INTEGER NOT NULL REFERENCES segments(id),
    start_time          TIME NOT NULL,
    interval_minutes    INTEGER NOT NULL CHECK (interval_minutes > 0),
    quantity            INTEGER NOT NULL CHECK (quantity > 0),
    is_active           BOOLEAN DEFAULT TRUE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE number_tests IS 'Números cadastrados para teste de conectividade com agendamento';
COMMENT ON COLUMN number_tests.interval_minutes IS 'Tempo em minutos entre cada discagem';
COMMENT ON COLUMN number_tests.quantity IS 'Quantidade de repetições do teste';

CREATE INDEX idx_number_tests_active ON number_tests(is_active) WHERE is_active = TRUE;
CREATE INDEX idx_number_tests_phone ON number_tests(phone_number);

/**
 * test_results
 * Resultado de cada execução de teste de conectividade.
 */
CREATE TABLE test_results (
    id                  BIGSERIAL PRIMARY KEY,
    number_test_id      BIGINT NOT NULL REFERENCES number_tests(id),
    executed_at         TIMESTAMP NOT NULL,
    sip_response_code   INTEGER,
    sip_response_reason VARCHAR(100),
    -- Status: SUCESSO | FALHA | OCUPADO | SEM_RESPOSTA | INVALIDO | TIMEOUT | INDISPONIVEL | RECUSADO
    status              VARCHAR(20) NOT NULL,
    execution_order     INTEGER NOT NULL DEFAULT 1,
    next_scheduled_at   TIMESTAMP,
    asterisk_call_id    VARCHAR(100),               -- UNIQUEID da chamada no Asterisk
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE test_results IS 'Histórico de resultados de cada execução de teste de conectividade';
COMMENT ON COLUMN test_results.status IS 'Status baseado no código SIP recebido';
COMMENT ON COLUMN test_results.next_scheduled_at IS 'Calculado pelo scheduler após cada execução';

CREATE INDEX idx_test_results_date ON test_results(executed_at);
CREATE INDEX idx_test_results_number ON test_results(number_test_id);
CREATE INDEX idx_test_results_status ON test_results(status);
CREATE INDEX idx_test_results_next ON test_results(next_scheduled_at);

-- =============================================================
-- MÓDULO 3 — Monitoramento de Infraestrutura
-- =============================================================

/**
 * alert_contacts
 * Números de plantão que receberão ligações de alerta.
 */
CREATE TABLE alert_contacts (
    id              SERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    phone_number    VARCHAR(20) NOT NULL,
    is_active       BOOLEAN DEFAULT TRUE,
    priority_order  INTEGER DEFAULT 1,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE alert_contacts IS 'Contatos de plantão que receberão ligações de alerta do Zabbix';
COMMENT ON COLUMN alert_contacts.priority_order IS 'Ordem de chamada em caso de múltiplos contatos';

/**
 * alert_calls
 * Registro de cada ligação de alerta originada a partir
 * de um incidente detectado no Zabbix.
 */
CREATE TABLE alert_calls (
    id                          BIGSERIAL PRIMARY KEY,
    call_date                   TIMESTAMP NOT NULL,
    phone_number                VARCHAR(20) NOT NULL,
    -- Status: ATENDIDA | NAO_ATENDIDA | FALHA | PENDENTE
    call_status                 VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    sip_response_code           INTEGER,
    sip_response_reason         VARCHAR(100),
    call_duration_secs          INTEGER DEFAULT 0,
    zabbix_trigger_id           VARCHAR(50) NOT NULL,
    zabbix_incident_summary     TEXT NOT NULL,
    zabbix_severity             VARCHAR(20),
    zabbix_host                 VARCHAR(200),
    audio_file_path             VARCHAR(500),
    telegram_message_content    TEXT,
    telegram_sent_at            TIMESTAMP,
    asterisk_call_id            VARCHAR(100),
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE alert_calls IS 'Ligações automáticas disparadas por incidentes graves do Zabbix';
COMMENT ON COLUMN alert_calls.zabbix_trigger_id IS 'ID do trigger no Zabbix para evitar duplicidade de alertas';

CREATE INDEX idx_alert_calls_date ON alert_calls(call_date);
CREATE INDEX idx_alert_calls_status ON alert_calls(call_status);
CREATE INDEX idx_alert_calls_zabbix ON alert_calls(zabbix_trigger_id);

-- =============================================================
-- DADOS INICIAIS (Seeds)
-- =============================================================

-- Perguntas padrão da URA (Módulo 1)
INSERT INTO ura_questions (question_order, question_text, jira_field_key, expected_values) VALUES
(1, 'Olá! Para registrar seu chamado, preciso de algumas informações. Qual é o seu nome completo?', 'customfield_nome_cliente', NULL),
(2, 'Qual é o número de telefone para contato?', 'customfield_telefone', NULL),
(3, 'Por favor, descreva brevemente o problema que está enfrentando.', 'description', NULL),
(4, 'Qual a urgência do seu chamado? Diga baixa, média ou alta.', 'priority', 'Baixa,Média,Alta'),
(5, 'Qual departamento deve tratar esse chamado? Diga T I, Comercial ou Financeiro.', 'customfield_departamento', 'TI,Comercial,Financeiro');
