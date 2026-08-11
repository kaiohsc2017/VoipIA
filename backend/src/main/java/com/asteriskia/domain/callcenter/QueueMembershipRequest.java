package com.asteriskia.domain.callcenter;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * QueueMembershipRequest — uma fila e a prioridade do atendente nela, usado no provisionamento
 * de agente a partir do cadastro de usuário (Fase 12.1 — {@code CreateUserRequest.queueMemberships}).
 */
public record QueueMembershipRequest(@NotNull Long queueId, @PositiveOrZero Integer priority) {}
