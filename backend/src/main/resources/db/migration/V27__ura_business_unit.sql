-- V27: BU de origem de uma URA — permite escopar Chamadas (call_records) por
-- BU do usuário logado via uras.business_unit_id (call_records.ura_id → uras).
-- Nullable: URAs sem BU definida (ex.: a legada id=1) não aparecem para
-- usuários restritos a BUs específicas, só para ADMIN — limitação aceita,
-- documentada no CLAUDE.md.

ALTER TABLE uras ADD COLUMN business_unit_id INTEGER REFERENCES business_units(id);

CREATE INDEX idx_uras_business_unit ON uras(business_unit_id);
