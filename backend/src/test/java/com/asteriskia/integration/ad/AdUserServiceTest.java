package com.asteriskia.integration.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.accessgroup.AccessGroup;
import com.asteriskia.domain.accessgroup.AccessGroupRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdUserServiceTest {

    @Mock private AdUserRepository adUserRepo;
    @Mock private AdGroupMappingRepository groupMappingRepo;
    @Mock private AccessGroupRepository accessGroupRepo;

    @InjectMocks private AdUserService adUserService;

    @Test
    void resolveAccessGroup_grupoAdMapeado_retornaGrupoMapeado() {
        AccessGroup mapeado = AccessGroup.builder().id(5).name("Supervisores").build();
        AdGroupMapping mapping = AdGroupMapping.builder().adGroupName("CN=Suporte,OU=G").accessGroup(mapeado).build();
        when(groupMappingRepo.findByAdGroupName("CN=Suporte,OU=G")).thenReturn(Optional.of(mapping));

        AccessGroup resolved = adUserService.resolveAccessGroup(List.of("CN=Suporte,OU=G"), 2);

        assertThat(resolved.getId()).isEqualTo(5);
    }

    @Test
    void resolveAccessGroup_semMapeamento_retornaGrupoPadrao() {
        when(groupMappingRepo.findByAdGroupName(any())).thenReturn(Optional.empty());
        AccessGroup padrao = AccessGroup.builder().id(2).name("Usuários").build();
        when(accessGroupRepo.findById(2)).thenReturn(Optional.of(padrao));

        AccessGroup resolved = adUserService.resolveAccessGroup(List.of("CN=OutroGrupo,OU=G"), 2);

        assertThat(resolved.getId()).isEqualTo(2);
    }

    @Test
    void upsertMirror_usuarioNovo_criaRegistro() {
        LdapUserAttributes attrs =
                new LdapUserAttributes(
                        "novo.ad", "Novo", "TI", "Matriz", "Analista", List.of("g1"), "chefe", "novo@x.com", "123",
                        "emp001");
        when(adUserRepo.findBySamAccountName("novo.ad")).thenReturn(Optional.empty());
        when(adUserRepo.save(any(AdUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AdUser saved = adUserService.upsertMirror(attrs);

        assertThat(saved.getSamAccountName()).isEqualTo("novo.ad");
        assertThat(saved.getDisplayName()).isEqualTo("Novo");
        assertThat(saved.getMemberOf()).isEqualTo("g1");
        assertThat(saved.getEmployeeId()).isEqualTo("emp001");
    }

    @Test
    void upsertMirror_usuarioExistente_atualizaSemDuplicar() {
        AdUser existente = AdUser.builder().id(1L).samAccountName("existe.ad").build();
        LdapUserAttributes attrs =
                new LdapUserAttributes(
                        "existe.ad", "Atualizado", null, null, null, List.of(), null, null, null, null);
        when(adUserRepo.findBySamAccountName("existe.ad")).thenReturn(Optional.of(existente));
        when(adUserRepo.save(any(AdUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AdUser saved = adUserService.upsertMirror(attrs);

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getDisplayName()).isEqualTo("Atualizado");
    }
}
