package com.asteriskia.domain.user;

import com.asteriskia.domain.callcenter.QueueMembershipRequest;
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
        Boolean accessIndeterminate,
        /** Fase 12.1 — se true, provisiona um CcAgent (ramal 4000-4999 + filas) para este
         * usuário, na mesma transação da criação. */
        Boolean callCenterAgent,
        /** Filas e prioridades do atendente — só considerado quando callCenterAgent=true. */
        List<QueueMembershipRequest> queueMemberships) {}
