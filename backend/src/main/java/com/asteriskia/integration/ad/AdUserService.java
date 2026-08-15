package com.asteriskia.integration.ad;

import com.asteriskia.domain.accessgroup.AccessGroup;
import com.asteriskia.domain.accessgroup.AccessGroupRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdUserService — consulta/atualiza o espelho local ({@code ad_users}) e resolve o grupo de acesso
 * de um usuário AD a partir do mapeamento de grupos ({@code ad_group_mappings}) ou do grupo padrão
 * configurado. Nunca consulta o AD ao vivo — só o {@link AdSyncScheduler} e o {@link LdapClient}
 * fazem isso; esta classe só lê/grava o espelho.
 */
@Service
@RequiredArgsConstructor
public class AdUserService {

    private final AdUserRepository adUserRepo;
    private final AdGroupMappingRepository groupMappingRepo;
    private final AccessGroupRepository accessGroupRepo;

    /** Busca no espelho local — usado por telas (screen pop, consulta de usuário AD). */
    public Optional<AdUser> findMirrored(String samAccountName) {
        return adUserRepo.findBySamAccountName(samAccountName);
    }

    /** Grava/atualiza o espelho a partir dos atributos vindos do AD (bind ou sync). */
    public AdUser upsertMirror(LdapUserAttributes attrs) {
        AdUser user =
                adUserRepo
                        .findBySamAccountName(attrs.samAccountName())
                        .orElseGet(
                                () ->
                                        AdUser.builder()
                                                .samAccountName(attrs.samAccountName())
                                                .build());
        user.setDisplayName(attrs.displayName());
        user.setDepartment(attrs.department());
        user.setOffice(attrs.office());
        user.setTitle(attrs.title());
        user.setMemberOf(String.join(";", attrs.memberOf()));
        user.setManagerSam(attrs.managerSam());
        user.setEmail(attrs.email());
        user.setTelephoneNumber(attrs.telephoneNumber());
        user.setEmployeeId(attrs.employeeId());
        user.setLastSyncedAt(LocalDateTime.now());
        return adUserRepo.save(user);
    }

    /**
     * Resolve o grupo de acesso para um usuário AD: primeiro grupo do AD (memberOf) que tiver
     * mapeamento cadastrado, senão o grupo padrão configurado ({@code defaultAccessGroupId}).
     *
     * <p><b>Limitação conhecida (Fase 1):</b> {@code memberOf} traz o DN completo do grupo (ex:
     * {@code "CN=Suporte,OU=Grupos,DC=empresa,DC=local"}), não o CN simples — {@code ad_group_name}
     * cadastrado em {@code AdGroupMapping} precisa ser o DN completo, exatamente como retornado pelo
     * AD, senão o mapeamento nunca casa e o usuário sempre cai no grupo padrão (fail-safe, não é
     * escalação de privilégio, mas é uma armadilha operacional sem UI de cadastro ainda — chega na
     * Fase 2).
     */
    public AccessGroup resolveAccessGroup(List<String> memberOf, int defaultAccessGroupId) {
        for (String adGroup : memberOf) {
            Optional<AdGroupMapping> mapping = groupMappingRepo.findByAdGroupName(adGroup);
            if (mapping.isPresent()) {
                return mapping.get().getAccessGroup();
            }
        }
        return accessGroupRepo
                .findById(defaultAccessGroupId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Grupo de acesso padrão do AD não existe: id="
                                                + defaultAccessGroupId));
    }
}
