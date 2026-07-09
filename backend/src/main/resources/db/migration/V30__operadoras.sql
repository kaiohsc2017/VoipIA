-- V30: cadastro dedicado de Operadoras — telas de Números 0800 e Linhas
-- passam a referenciar operadoras cadastradas em vez de texto livre. Novo
-- resource_key RBAC ("telecom.operadoras") liberado para os dois grupos seed.

CREATE TABLE operadoras (
    id          SERIAL PRIMARY KEY,
    nome        VARCHAR(200) NOT NULL UNIQUE,
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE operadoras IS 'Operadoras de telecom cadastradas (bloco Cadastros)';

-- Migra os valores de texto livre já usados (idempotente — sem dados reais
-- em produção até este ponto, mas por segurança em caso de uso concorrente).
INSERT INTO operadoras (nome)
SELECT DISTINCT trim(operadora) FROM numeros_0800 WHERE operadora IS NOT NULL AND trim(operadora) <> ''
UNION
SELECT DISTINCT trim(operadora) FROM numero_0800_regenerados WHERE operadora IS NOT NULL AND trim(operadora) <> ''
UNION
SELECT DISTINCT trim(operadora) FROM linhas WHERE operadora IS NOT NULL AND trim(operadora) <> ''
ON CONFLICT (nome) DO NOTHING;

-- Fallback para linhas com operadora em branco: a coluna era NOT NULL, mas
-- sem @NotBlank no lado Java — string vazia sempre foi aceita. Sem isso, o
-- ALTER COLUMN ... SET NOT NULL abaixo falharia caso exista alguma linha
-- residual com operadora = ''.
INSERT INTO operadoras (nome) VALUES ('Não informado') ON CONFLICT (nome) DO NOTHING;

ALTER TABLE numeros_0800 ADD COLUMN operadora_id INTEGER REFERENCES operadoras(id);
UPDATE numeros_0800 n SET operadora_id = o.id FROM operadoras o WHERE o.nome = trim(n.operadora);
UPDATE numeros_0800 SET operadora_id = (SELECT id FROM operadoras WHERE nome = 'Não informado') WHERE operadora_id IS NULL;
ALTER TABLE numeros_0800 ALTER COLUMN operadora_id SET NOT NULL;
ALTER TABLE numeros_0800 DROP COLUMN operadora;

ALTER TABLE numero_0800_regenerados ADD COLUMN operadora_id INTEGER REFERENCES operadoras(id);
UPDATE numero_0800_regenerados r SET operadora_id = o.id FROM operadoras o WHERE o.nome = trim(r.operadora);
ALTER TABLE numero_0800_regenerados DROP COLUMN operadora;

ALTER TABLE linhas ADD COLUMN operadora_id INTEGER REFERENCES operadoras(id);
UPDATE linhas l SET operadora_id = o.id FROM operadoras o WHERE o.nome = trim(l.operadora);
UPDATE linhas SET operadora_id = (SELECT id FROM operadoras WHERE nome = 'Não informado') WHERE operadora_id IS NULL;
ALTER TABLE linhas ALTER COLUMN operadora_id SET NOT NULL;
ALTER TABLE linhas DROP COLUMN operadora;

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write) VALUES
    (1, 'telecom.operadoras', TRUE, TRUE),
    (2, 'telecom.operadoras', TRUE, TRUE);
