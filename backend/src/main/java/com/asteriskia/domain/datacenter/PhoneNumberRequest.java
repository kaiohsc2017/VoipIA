package com.asteriskia.domain.datacenter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PhoneNumberRequest — corpo de criação/edição de um número no DATACENTER.
 *
 * Cliente é resolvido por {@code clientId} (existente) OU {@code newClientName}
 * (texto livre — cria um Cliente novo, só com o nome, se ainda não existir um
 * com nome equivalente). Operação e Segmento são opcionais: deixá-los em
 * branco marca o número como "pendente" (ver PhoneNumberSyncService).
 */
public record PhoneNumberRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9+()\\-\\s]{1,20}$", message = "Use apenas dígitos, +, (), - e espaços")
        String phoneNumber,
        @NotNull NumberType numberType,
        @NotNull Integer businessUnitId,
        Integer clientId,
        @Size(max = 200, message = "Nome do cliente deve ter no máximo 200 caracteres")
        String newClientName,
        Integer operationId,
        Integer segmentId,
        String observation,
        Boolean isActive
) {
}
