-- V88: fecha o gap de BU no agendamento de relatório do Call Center (Fase 9c.6). O agendamento
-- roda em background (fora de uma requisição autenticada), então a BU de quem criou precisa ser
-- congelada na criação — mesmo padrão de escopo congelado já usado para fila/agente/período em
-- cc_report_schedules. Tabela associativa N:N (um agendamento criado por um usuário com múltiplas
-- BUs deve reter todas), mesmo padrão de user_business_units (V26)/client_business_units (V25).
-- Ausência de linha == criado por ADMIN sem restrição de BU (mesma semântica de
-- BusinessUnitContext.currentBusinessUnitIds() vazio/null usada no resto do domínio).

CREATE TABLE cc_report_schedule_business_units (
    schedule_id       BIGINT  NOT NULL REFERENCES cc_report_schedules(id) ON DELETE CASCADE,
    business_unit_id  INTEGER NOT NULL REFERENCES business_units(id) ON DELETE CASCADE,
    PRIMARY KEY (schedule_id, business_unit_id)
);

CREATE INDEX idx_cc_report_schedule_business_units_bu ON cc_report_schedule_business_units(business_unit_id);
