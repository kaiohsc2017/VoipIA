package com.asteriskia.domain.datacenter;

/**
 * PhoneNumberSaveResult — resposta de criação/edição no DATACENTER, com
 * sinalizações para a UI: cliente novo criado automaticamente e/ou teste de
 * conectividade criado com o template padrão do sistema (segmento sem
 * template próprio configurado).
 */
public record PhoneNumberSaveResult(
        PhoneNumber phoneNumber,
        boolean clientCreated,
        boolean usedSystemDefaultTemplate
) {
}
