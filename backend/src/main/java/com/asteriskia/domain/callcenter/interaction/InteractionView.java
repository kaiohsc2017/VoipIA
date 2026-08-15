package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.integration.ad.AdUser;
import java.time.LocalDateTime;

/** InteractionView — resposta de leitura de uma interação (tela de Desktop do Agente).
 * {@code identity} é {@code null} quando o contato não foi resolvido contra o AD (Fase 14) —
 * estado normal, o frontend deve exibir "Contato não identificado" nesse caso. */
public record InteractionView(
        Long id,
        String queueName,
        String ani,
        Direction direction,
        LocalDateTime queuedAt,
        LocalDateTime answeredAt,
        LocalDateTime endedAt,
        String dispositionLabel,
        ContactIdentityView identity) {

    public static InteractionView from(CcInteraction entity) {
        return from(entity, null);
    }

    public static InteractionView from(CcInteraction entity, AdUser adUser) {
        return new InteractionView(
                entity.getId(),
                entity.getQueue() == null ? null : entity.getQueue().getDisplayName(),
                entity.getAni(),
                entity.getDirection(),
                entity.getQueuedAt(),
                entity.getAnsweredAt(),
                entity.getEndedAt(),
                entity.getDisposition() == null ? null : entity.getDisposition().getLabel(),
                ContactIdentityView.from(adUser, entity.getIdentitySource()));
    }

    /** Bloco de identidade resolvida (screen pop, Fase 14) — todos os campos vêm direto de
     * {@code ad_users}, nunca consultados ao vivo no AD (mesma decisão D4 já aplicada ao resto
     * do módulo). */
    public record ContactIdentityView(
            String samAccountName,
            String displayName,
            String department,
            String office,
            String title,
            String managerSam,
            String email,
            String telephoneNumber,
            String source) {

        static ContactIdentityView from(AdUser adUser, String source) {
            if (adUser == null) {
                return null;
            }
            return new ContactIdentityView(
                    adUser.getSamAccountName(),
                    adUser.getDisplayName(),
                    adUser.getDepartment(),
                    adUser.getOffice(),
                    adUser.getTitle(),
                    adUser.getManagerSam(),
                    adUser.getEmail(),
                    adUser.getTelephoneNumber(),
                    source);
        }
    }
}
