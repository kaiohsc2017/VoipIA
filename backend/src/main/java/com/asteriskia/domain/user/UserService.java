package com.asteriskia.domain.user;

import com.asteriskia.domain.accessgroup.AccessGroup;
import com.asteriskia.domain.accessgroup.AccessGroupRepository;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * UserService — regras de negócio do cadastro de usuários, extraídas de UserController (fase 2 da
 * refatoração): resolução de grupo de acesso a partir do role legado, resolução/validação de
 * Unidades de Negócio e a regra de janela de acesso (expiração x indeterminado). Sem efeito
 * colateral de persistência de AppUser nem auditoria — isso continua no controller, que orquestra a
 * requisição HTTP.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final AccessGroupRepository accessGroupRepo;
    private final BusinessUnitRepository businessUnitRepo;

    // Grupos de sistema seedados na V22 — usados enquanto o role legado
    // (ADMIN|USER) ainda pilota a UI de usuários (até a Fase 5 do RBAC granular).
    private static final int GROUP_ADMINISTRADORES = 1;
    private static final int GROUP_USUARIOS = 2;

    // Regra de negócio: acesso com prazo determinado nunca passa de 60 dias.
    private static final int MAX_ACCESS_DAYS = 60;

    public AccessGroup resolveGroupForRole(String role) {
        int groupId = "ADMIN".equals(role) ? GROUP_ADMINISTRADORES : GROUP_USUARIOS;
        return accessGroupRepo
                .findById(groupId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Grupo de acesso seed ausente: id=" + groupId));
    }

    /**
     * Resolve o grupo de acesso do usuário: se {@code accessGroupId} for informado (grupo
     * customizado escolhido explicitamente pela UI), tem precedência sobre o fallback binário
     * {@link #resolveGroupForRole(String)} — que continua existindo para o fluxo legado
     * ADMIN|USER sem grupo customizado selecionado.
     */
    public AccessGroup resolveAccessGroup(Integer accessGroupId, String role) {
        if (accessGroupId != null) {
            return accessGroupRepo
                    .findById(accessGroupId)
                    .orElseThrow(
                            () ->
                                    new IllegalArgumentException(
                                            "Grupo de acesso não encontrado: id=" + accessGroupId));
        }
        return resolveGroupForRole(role);
    }

    /** Resolve os IDs de BU informados, validando que todos existem. */
    public Set<BusinessUnit> resolveBusinessUnits(List<Integer> ids) {
        List<BusinessUnit> found = businessUnitRepo.findAllById(ids);
        if (found.size() != Set.copyOf(ids).size()) {
            throw new IllegalArgumentException(
                    "Uma ou mais Unidades de Negócio informadas não existem.");
        }
        return new HashSet<>(found);
    }

    /**
     * Valida a regra "expiração de acesso XOR indeterminado": exatamente um dos dois deve estar
     * preenchido — indeterminado=true com data ausente, ou uma data futura de até 60 dias com
     * indeterminado=false.
     */
    public void validateAccessWindow(LocalDate accessExpiresAt, boolean accessIndeterminate) {
        if (accessIndeterminate) {
            if (accessExpiresAt != null) {
                throw new IllegalArgumentException(
                        "Acesso indeterminado não pode ter data de expiração.");
            }
            return;
        }
        if (accessExpiresAt == null) {
            throw new IllegalArgumentException(
                    "Informe a data de expiração do acesso ou marque acesso indeterminado.");
        }
        LocalDate today = LocalDate.now();
        if (accessExpiresAt.isBefore(today)) {
            throw new IllegalArgumentException("A data de expiração não pode estar no passado.");
        }
        if (accessExpiresAt.isAfter(today.plusDays(MAX_ACCESS_DAYS))) {
            throw new IllegalArgumentException(
                    "A data de expiração não pode passar de "
                            + MAX_ACCESS_DAYS
                            + " dias a partir de hoje.");
        }
    }
}
