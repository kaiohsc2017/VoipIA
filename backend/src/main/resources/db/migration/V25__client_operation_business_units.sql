-- V25: vínculo opcional N:N entre Cliente/Operação e Unidade de Negócio (BU).
-- Cadastro de Cliente ganha combo de BU; cadastro de Operação ganha combo de
-- Cliente (já existente via client_operations) + combo de BU.

CREATE TABLE client_business_units (
    client_id         INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    business_unit_id  INTEGER NOT NULL REFERENCES business_units(id) ON DELETE CASCADE,
    PRIMARY KEY (client_id, business_unit_id)
);

CREATE TABLE operation_business_units (
    operation_id      INTEGER NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    business_unit_id  INTEGER NOT NULL REFERENCES business_units(id) ON DELETE CASCADE,
    PRIMARY KEY (operation_id, business_unit_id)
);

CREATE INDEX idx_client_business_units_bu ON client_business_units(business_unit_id);
CREATE INDEX idx_operation_business_units_bu ON operation_business_units(business_unit_id);
