-- V42: configuração do alerta de gasto em USD por frente do módulo Financeiro (URA,
-- Insights, Análise Sob Demanda). Um scheduler diário (CostAlertScheduler) compara o gasto
-- do mês corrente de cada frente habilitada ao limite configurado e dispara um alerta via
-- Telegram (mesmo canal já usado pelos alertas de Zabbix e pela busca automática de preço
-- de IA) quando o limite é ultrapassado — no máximo uma vez por mês por frente
-- (last_notified_month evita repetir o alerta em todo run do scheduler).

CREATE TABLE financeiro_cost_alerts (
    scope               VARCHAR(20) PRIMARY KEY,
    threshold_usd       NUMERIC(12,2) NOT NULL DEFAULT 0,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    last_notified_month VARCHAR(7),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_by          VARCHAR(100),
    CONSTRAINT chk_financeiro_cost_alerts_scope CHECK (scope IN ('ura', 'insights', 'envios'))
);

COMMENT ON TABLE financeiro_cost_alerts IS 'Config do alerta de gasto de IA por frente do módulo Financeiro — ver CostAlertScheduler';
COMMENT ON COLUMN financeiro_cost_alerts.last_notified_month IS 'yyyy-MM do último alerta enviado — evita repetir o alerta no mesmo mês';

INSERT INTO financeiro_cost_alerts (scope, threshold_usd, enabled) VALUES
    ('ura', 0, FALSE),
    ('insights', 0, FALSE),
    ('envios', 0, FALSE);
