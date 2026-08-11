-- V10: Tabela de configurações dinâmicas do sistema
-- Substitui leitura de variáveis de ambiente (@Value) nos serviços de integração.
-- Os serviços passam a consultar esta tabela em tempo real — sem restart de container.

CREATE TABLE system_config (
    key         VARCHAR(100)  PRIMARY KEY,
    value       TEXT          NOT NULL DEFAULT '',
    is_secret   BOOLEAN       NOT NULL DEFAULT FALSE,
    description VARCHAR(255),
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(100)  NOT NULL DEFAULT 'system'
);

COMMENT ON TABLE  system_config             IS 'Configurações de integração lidas em runtime — sem restart de container';
COMMENT ON COLUMN system_config.key         IS 'Nome da variável (igual ao .env para facilitar migração)';
COMMENT ON COLUMN system_config.value       IS 'Valor atual. Segredos são armazenados em plain-text (protegidos pelo banco).';
COMMENT ON COLUMN system_config.is_secret   IS 'Se true, valor é mascarado na API GET';
COMMENT ON COLUMN system_config.updated_at  IS 'Última atualização (auto-updated pelo trigger)';

-- Trigger para auto-atualizar updated_at
CREATE OR REPLACE FUNCTION update_system_config_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_system_config_updated_at
    BEFORE UPDATE ON system_config
    FOR EACH ROW EXECUTE FUNCTION update_system_config_timestamp();

-- Seed: Jira
INSERT INTO system_config (key, value, is_secret, description) VALUES
('JIRA_BASE_URL',    '', FALSE, 'URL base da instância Jira Cloud (ex: https://empresa.atlassian.net)'),
('JIRA_USER_EMAIL',  '', FALSE, 'E-mail do usuário Jira para autenticação Basic'),
('JIRA_API_TOKEN',   '', TRUE,  'API Token Jira (gerado em id.atlassian.com)'),
('JIRA_PROJECT_KEY', '', FALSE, 'Chave do projeto Jira onde os chamados serão criados');

-- Seed: Zabbix
INSERT INTO system_config (key, value, is_secret, description) VALUES
('ZABBIX_API_URL',               '', FALSE, 'URL da API JSON-RPC do Zabbix'),
('ZABBIX_USER',                  '', FALSE, 'Usuário de leitura da API Zabbix'),
('ZABBIX_PASSWORD',              '', TRUE,  'Senha do usuário Zabbix'),
('ZABBIX_MIN_SEVERITY',          '4', FALSE, 'Severidade mínima: 2=Warning 3=Average 4=High 5=Disaster'),
('ZABBIX_POLL_INTERVAL_MINUTES', '5', FALSE, 'Intervalo de polling em minutos');

-- Seed: Telegram
INSERT INTO system_config (key, value, is_secret, description) VALUES
('TELEGRAM_BOT_TOKEN', '', TRUE,  'Token do bot Telegram (obtido via @BotFather)'),
('TELEGRAM_CHAT_ID',   '', FALSE, 'Chat ID do canal/grupo de destino');

-- Seed: Gemini
INSERT INTO system_config (key, value, is_secret, description) VALUES
('GEMINI_API_KEY',   '', TRUE,  'Chave de API Google Gemini (aistudio.google.com)'),
('GEMINI_MODEL_STT', 'gemini-2.0-flash',              FALSE, 'Modelo para Speech-to-Text'),
('GEMINI_MODEL_LLM', 'gemini-2.0-flash',              FALSE, 'Modelo para geração de texto'),
('GEMINI_MODEL_TTS', 'gemini-2.5-flash-preview-tts',  FALSE, 'Modelo para Text-to-Speech');

-- Seed: SIP Trunk
INSERT INTO system_config (key, value, is_secret, description) VALUES
('SIP_TRUNK_HOST',        '', FALSE, 'Host/IP da operadora SIP'),
('SIP_TRUNK_USER',        '', FALSE, 'Usuário do tronco SIP'),
('SIP_TRUNK_PASSWORD',    '', TRUE,  'Senha do tronco SIP'),
('SIP_TRUNK_FROM_DOMAIN', '', FALSE, 'From Domain SIP (geralmente igual ao host)'),
('AST_OUTBOUND_TRUNK',    'tronco-sip',       FALSE, 'Nome do trunk no Asterisk pjsip.conf'),
('AST_OUTBOUND_CONTEXT',  'discagem-sainte',  FALSE, 'Contexto de discagem de saída');
