package com.asteriskia.domain.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record CreateUserRequest(
        @NotBlank(message = "Username obrigatório") String username,
        @NotBlank(message = "Senha obrigatória")
                @Size(min = 6, message = "Senha mínima: 6 caracteres")
                String password,
        @NotBlank(message = "Nome de exibição obrigatório") String displayName,
        String role,
        @NotEmpty(message = "Selecione ao menos uma Unidade de Negócio (BU)")
                List<Integer> businessUnitIds,
        LocalDate accessExpiresAt,
        Boolean accessIndeterminate) {}
