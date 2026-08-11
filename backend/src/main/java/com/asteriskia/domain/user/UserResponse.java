package com.asteriskia.domain.user;

import com.asteriskia.domain.masterdata.BusinessUnit;
import java.util.List;

public record UserResponse(
        Integer id,
        String username,
        String displayName,
        Integer extension,
        String extensionPassword,
        Boolean isActive,
        String role,
        String createdAt,
        List<Integer> businessUnitIds,
        String accessExpiresAt,
        Boolean accessIndeterminate,
        Boolean totpEnabled) {
    static UserResponse from(AppUser u) {
        return new UserResponse(
                u.getId(),
                u.getUsername(),
                u.getDisplayName(),
                u.getExtension(),
                // Achado de segurança: não gravar mais a senha em claro aqui —
                // só disponível sob demanda via GET /{id}/extension-password.
                null,
                u.getIsActive(),
                u.getRole(),
                u.getCreatedAt() != null ? u.getCreatedAt().toString() : null,
                u.getBusinessUnits().stream().map(BusinessUnit::getId).toList(),
                u.getAccessExpiresAt() != null ? u.getAccessExpiresAt().toString() : null,
                u.getAccessIndeterminate(),
                u.getTotpEnabled());
    }
}
