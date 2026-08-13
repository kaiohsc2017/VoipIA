-- Fase 19 do plano Call Center Parte III — armazenamento genérico de configuração do módulo:
-- ranges de ramal (agente/fila/fluxo) e o interruptor global de pesquisa de satisfação (NPS).
-- Deliberadamente uma tabela chave/valor: evita criar uma tabela por parâmetro à medida que
-- novas fases (21, 23...) precisarem de configuração global própria.
CREATE TABLE cc_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL,
    setting_value TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_cc_settings_key ON cc_settings (setting_key);

COMMENT ON TABLE cc_settings IS
    'Configuração chave/valor do módulo Call Center (ranges de ramal, interruptor global de NPS). '
    'Ausência de linha = usa o default do código (mesmo comportamento de antes desta migration).';
