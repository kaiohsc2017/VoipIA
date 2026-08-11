-- =============================================================
-- V2__seed_master_data.sql — Dados Iniciais de Master Data
-- Executado automaticamente pelo PostgreSQL na primeira inicialização
-- ON CONFLICT DO NOTHING garante idempotência
-- =============================================================

-- -----------------------------------------------------------
-- Business Units (Unidades de Negócio)
-- -----------------------------------------------------------
INSERT INTO business_units (name, description, is_active) VALUES
    ('Tecnologia',       'Área de TI e Infraestrutura',          true),
    ('Operações',        'Suporte e operações de campo',          true),
    ('Comercial',        'Vendas e atendimento ao cliente',       true),
    ('Financeiro',       'Contabilidade e controladoria',         true),
    ('RH',               'Recursos Humanos e benefícios',         true)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------
-- Segmentos
-- -----------------------------------------------------------
INSERT INTO segments (name, description, is_active) VALUES
    ('Enterprise',  'Grandes empresas e corporações',            true),
    ('SMB',         'Pequenas e médias empresas',                 true),
    ('Governo',     'Órgãos públicos e autarquias',              true),
    ('Varejo',      'Comércio e lojas',                          true)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------
-- Clientes de Exemplo
-- -----------------------------------------------------------
INSERT INTO clients (name, description, is_active) VALUES
    ('AcmeCorp S.A.',    'Multinacional de manufatura',           true),
    ('GovBR',            'Órgão governamental de exemplo',        true)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------
-- Operações de Exemplo
-- -----------------------------------------------------------
INSERT INTO operations (name, description, is_active) VALUES
    ('Suporte N1',       'Suporte técnico de primeiro nível',     true),
    ('Suporte N2',       'Suporte técnico de segundo nível',      true),
    ('Comercial SAC',    'Atendimento comercial ao cliente',      true)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------
-- Vincula Operações a Clientes (M:N)
-- -----------------------------------------------------------
INSERT INTO client_operations (client_id, operation_id)
SELECT c.id, o.id
FROM clients c, operations o
WHERE c.name = 'AcmeCorp S.A.'
  AND o.name IN ('Suporte N1', 'Suporte N2')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------
-- NumberTests de Exemplo (Módulo 2)
-- Requer FKs: business_unit, client, operation, segment
-- -----------------------------------------------------------
INSERT INTO number_tests (
    phone_number, business_unit_id, client_id, operation_id, segment_id,
    start_time, interval_minutes, quantity, is_active
)
SELECT
    '+5511900000001',
    (SELECT id FROM business_units WHERE name = 'Tecnologia'),
    (SELECT id FROM clients      WHERE name = 'AcmeCorp S.A.'),
    (SELECT id FROM operations   WHERE name = 'Suporte N1'),
    (SELECT id FROM segments     WHERE name = 'Enterprise'),
    '08:00:00', 240, 3, true
WHERE NOT EXISTS (
    SELECT 1 FROM number_tests WHERE phone_number = '+5511900000001'
);

INSERT INTO number_tests (
    phone_number, business_unit_id, client_id, operation_id, segment_id,
    start_time, interval_minutes, quantity, is_active
)
SELECT
    '+5531900000002',
    (SELECT id FROM business_units WHERE name = 'Operações'),
    (SELECT id FROM clients      WHERE name = 'GovBR'),
    (SELECT id FROM operations   WHERE name = 'Comercial SAC'),
    (SELECT id FROM segments     WHERE name = 'Governo'),
    '09:00:00', 360, 2, false
WHERE NOT EXISTS (
    SELECT 1 FROM number_tests WHERE phone_number = '+5531900000002'
);

-- -----------------------------------------------------------
-- Contatos de Plantão (Módulo 3 — AlertContact)
-- -----------------------------------------------------------
INSERT INTO alert_contacts (name, phone_number, is_active, priority_order) VALUES
    ('NOC Nível 1',   '+5511900000001', true,  1),
    ('NOC Nível 2',   '+5511900000002', true,  2),
    ('Gerente de TI', '+5511900000003', false, 3)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------
-- Perguntas URA (Módulo 1 — UraQuestion)
-- Campos: question_order, question_text, jira_field_key, expected_values, is_active
-- -----------------------------------------------------------
INSERT INTO ura_questions (question_order, question_text, jira_field_key, expected_values, is_active) VALUES
    (1, 'Por favor, diga o nome completo da empresa.',           'customfield_nome_cliente', NULL,               true),
    (2, 'Qual é o nome do responsável técnico?',                 'customfield_responsavel',  NULL,               true),
    (3, 'Descreva brevemente o problema que está enfrentando.',  'description',              NULL,               true),
    (4, 'Qual a prioridade? Diga alta, média ou baixa.',         'priority',                 'alta,média,baixa', true)
ON CONFLICT DO NOTHING;
