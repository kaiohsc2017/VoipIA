package com.asteriskia.domain.accessgroup;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * AccessGroupService — resolve a matriz de permissões de um grupo para o
 * formato compacto embutido na claim "perm" do JWT: {resource_key: "r"|"w"|"rw"}.
 * Entradas sem leitura nem escrita são omitidas para manter o token pequeno.
 */
@Service
@RequiredArgsConstructor
public class AccessGroupService {

    private final AccessGroupRepository groupRepo;
    private final AccessGroupPermissionRepository permissionRepo;

    public Map<String, String> permissionsFor(AccessGroup group) {
        if (group == null) {
            return Map.of();
        }
        return permissionRepo.findByGroupId(group.getId()).stream()
                .filter(p -> Boolean.TRUE.equals(p.getCanRead()) || Boolean.TRUE.equals(p.getCanWrite()))
                .collect(Collectors.toMap(
                        AccessGroupPermission::getResourceKey,
                        p -> (Boolean.TRUE.equals(p.getCanRead()) ? "r" : "")
                                + (Boolean.TRUE.equals(p.getCanWrite()) ? "w" : "")
                ));
    }

    /** Grupo seed "Administradores" (id=1) — usado pela conta mestre de fallback via env. */
    public AccessGroup administradores() {
        return groupRepo.findById(1)
                .orElseThrow(() -> new IllegalStateException("Grupo de acesso seed ausente: Administradores (id=1)"));
    }
}
