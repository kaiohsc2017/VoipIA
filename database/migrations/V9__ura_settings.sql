-- V9: Mensagens configuráveis do fluxo da URA
-- Substitui textos hardcoded no jira_call_flow.py pelas mensagens gerenciadas via tela

CREATE TABLE ura_settings (
    key        VARCHAR(50)   PRIMARY KEY,
    value      TEXT          NOT NULL DEFAULT '',
    label      VARCHAR(100)  NOT NULL,
    required   BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  ura_settings             IS 'Mensagens configuráveis do fluxo de URA (boas-vindas, informativa, encerramento)';
COMMENT ON COLUMN ura_settings.key         IS 'Identificador único da mensagem (boas_vindas, informativa, encerramento)';
COMMENT ON COLUMN ura_settings.value       IS 'Texto que será sintetizado em voz (TTS). Vazio = não reproduzir (só para informativa).';
COMMENT ON COLUMN ura_settings.required    IS 'Se true, valor não pode ser vazio. Se false (informativa), é opcional.';

INSERT INTO ura_settings (key, label, required, value) VALUES
(
    'boas_vindas',
    'Mensagem de boas-vindas',
    TRUE,
    'Bem-vindo ao sistema de atendimento. Posso ajudar com consultas de pedidos, abertura de chamados ou suporte. Como posso te ajudar hoje?'
),
(
    'informativa',
    'Mensagem informativa',
    FALSE,
    ''
),
(
    'encerramento',
    'Mensagem de encerramento',
    TRUE,
    'Seu chamado foi aberto com sucesso. O número do seu protocolo é {protocolo}. Em breve nossa equipe entrará em contato. Obrigado!'
);
