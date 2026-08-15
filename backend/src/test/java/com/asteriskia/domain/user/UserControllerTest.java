package com.asteriskia.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.accessgroup.AccessGroup;
import com.asteriskia.domain.accessgroup.AccessGroupRepository;
import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.callcenter.CallCenterAgentProvisioningService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UserControllerTest — teste de CARACTERIZAÇÃO (fase 2 da refatoração).
 *
 * <p>Objetivo: travar o comportamento ATUAL do CRUD de usuários/ramais antes de qualquer refactor
 * estrutural — não julgar se está certo, só impedir que uma refatoração futura mude o comportamento
 * em silêncio. Segue o mesmo padrão de {@code CadastroControllerTest}: {@code @WebMvcTest} +
 * {@code @AutoConfigureMockMvc(addFilters = false)} — a FilterChainProxy do Spring Security fica
 * desligada, então a autorização por {@code hasAnyAuthority(...)} (ROLE_ADMIN /
 * PERM_WRITE_telecom.users, configurada em SecurityConfig) NÃO é exercitada aqui: ela vive fora do
 * controller e não tem como ser testada nesta fatia sem subir o filtro real. O que este teste trava
 * é o comportamento do controller em si — validação, mapeamento de DTO, campos sensíveis, regras de
 * negócio (janela de acesso, resolução de grupo/BU).
 */
@WebMvcTest(UserController.class)
@Import(UserService.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private AppUserRepository userRepo;

    @MockBean private AccessGroupRepository accessGroupRepo;

    @MockBean private BusinessUnitRepository businessUnitRepo;

    @MockBean private AuditService auditService;

    @MockBean private JwtService jwtService;

    @MockBean private CallCenterAgentProvisioningService agentProvisioningService;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    private static BusinessUnit bu(Integer id) {
        return BusinessUnit.builder().id(id).name("BU " + id).isActive(true).build();
    }

    private static AccessGroup group(Integer id, String name) {
        return AccessGroup.builder().id(id).name(name).isSystem(true).build();
    }

    private static AppUser.AppUserBuilder baseUser() {
        return AppUser.builder()
                .id(1)
                .username("kaio")
                .passwordHash(ENCODER.encode("senha-secreta"))
                .displayName("Kaio Correa")
                .extension(9001)
                .isActive(true)
                .role("USER")
                .accessGroup(group(2, "Usuários"))
                .businessUnits(Set.of(bu(5)))
                .accessExpiresAt(null)
                .accessIndeterminate(true)
                .totpEnabled(false)
                .firstLoginCompleted(true);
    }

    private void mockGrupos() {
        when(accessGroupRepo.findById(1)).thenReturn(Optional.of(group(1, "Administradores")));
        when(accessGroupRepo.findById(2)).thenReturn(Optional.of(group(2, "Usuários")));
    }

    // =========================================================================
    // GET — listagem, busca por ID, senha de ramal sob demanda
    // =========================================================================

    @Nested
    class ListagemEBusca {

        @Test
        @WithMockUser(roles = "ADMIN")
        void listUsers_deveRetornarTodosSemExpuserSenha() throws Exception {
            AppUser u = baseUser().build();
            when(userRepo.findAll()).thenReturn(List.of(u));

            mockMvc.perform(get("/api/v1/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].username").value("kaio"))
                    .andExpect(jsonPath("$[0].extension").value(9001))
                    // Achado de segurança já corrigido (comentário no controller): a listagem
                    // NUNCA deve trazer a senha do ramal — só o endpoint dedicado abaixo.
                    .andExpect(jsonPath("$[0].extensionPassword").doesNotExist())
                    .andExpect(jsonPath("$[0].accessIndeterminate").value(true));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void getUser_existente_deveRetornarUsuarioSemSenha() throws Exception {
            AppUser u = baseUser().build();
            when(userRepo.findById(1)).thenReturn(Optional.of(u));

            mockMvc.perform(get("/api/v1/users/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.businessUnitIds[0]").value(5))
                    .andExpect(jsonPath("$.extensionPassword").doesNotExist());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void getUser_inexistente_deveRetornar404() throws Exception {
            when(userRepo.findById(99)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/users/99")).andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void getExtensionPassword_existente_deveRetornarSenhaDerivadaDoRamal() throws Exception {
            AppUser u = baseUser().build();
            when(userRepo.findById(1)).thenReturn(Optional.of(u));

            // Comportamento atual documentado (achado de segurança pré-existente, não corrigido
            // aqui): a "senha do ramal" devolvida é uma fórmula fixa e previsível
            // ("webrtc" + extensão + "pass"), não a senha SIP real configurada no .env/pjsip.
            // Travando o valor atual — decisão de corrigir isso é separada desta refatoração.
            mockMvc.perform(get("/api/v1/users/1/extension-password"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.extensionPassword").value("webrtc9001pass"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void getExtensionPassword_inexistente_deveRetornar404() throws Exception {
            when(userRepo.findById(99)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/users/99/extension-password"))
                    .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // POST — criação
    // =========================================================================

    @Nested
    class Criacao {

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_sucesso_deveRetornar201ComRamalAtribuidoENumeroDeSenhaOculto()
                throws Exception {
            mockGrupos();
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(userRepo.findNextExtension(9001)).thenReturn(9002);
            when(businessUnitRepo.findAllById(List.of(5))).thenReturn(List.of(bu(5)));
            AppUser saved =
                    baseUser()
                            .id(2)
                            .username("novo")
                            .extension(9002)
                            .accessIndeterminate(true)
                            .build();
            when(userRepo.save(any(AppUser.class))).thenReturn(saved);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Usuário Novo",
                        "role": "USER",
                        "businessUnitIds": [5],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.username").value("novo"))
                    .andExpect(jsonPath("$.extension").value(9002))
                    .andExpect(jsonPath("$.extensionPassword").doesNotExist());

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            AppUser toSave = captor.getValue();
            // Senha nunca é persistida em texto puro — sempre um hash BCrypt.
            assertThat(toSave.getPasswordHash()).isNotEqualTo("senha123");
            assertThat(toSave.getPasswordHash()).matches("^\\$2[aby]\\$.*");
            assertThat(toSave.getExtension()).isEqualTo(9002);
            assertThat(toSave.getAccessGroup().getId()).isEqualTo(2);

            verify(auditService).log(any(), eq("USER_CREATE"), contains("novo"), eq(true));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_roleAdmin_deveResolverGrupoAdministradores() throws Exception {
            mockGrupos();
            when(userRepo.findByUsername("admin2")).thenReturn(Optional.empty());
            when(userRepo.findNextExtension(9001)).thenReturn(9003);
            when(businessUnitRepo.findAllById(List.of(5))).thenReturn(List.of(bu(5)));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body =
                    """
                    {
                        "username": "admin2",
                        "password": "senha123",
                        "displayName": "Admin Dois",
                        "role": "ADMIN",
                        "businessUnitIds": [5],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getAccessGroup().getId()).isEqualTo(1);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_comAccessGroupIdCustomizado_devePrevalecerSobreRole() throws Exception {
            mockGrupos();
            when(accessGroupRepo.findById(7))
                    .thenReturn(Optional.of(group(7, "Supervisores")));
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(userRepo.findNextExtension(9001)).thenReturn(9003);
            when(businessUnitRepo.findAllById(List.of(5))).thenReturn(List.of(bu(5)));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo Usuário",
                        "role": "USER",
                        "accessGroupId": 7,
                        "businessUnitIds": [5],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessGroupId").value(7))
                    .andExpect(jsonPath("$.accessGroupName").value("Supervisores"));

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getAccessGroup().getId()).isEqualTo(7);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_accessGroupIdInexistente_deveRetornar400ENaoSalvar() throws Exception {
            when(accessGroupRepo.findById(999)).thenReturn(Optional.empty());
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(businessUnitRepo.findAllById(List.of(5))).thenReturn(List.of(bu(5)));

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo Usuário",
                        "accessGroupId": 999,
                        "businessUnitIds": [5],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(authorities = "PERM_WRITE_telecom.users")
        void createUser_naoAdminComAccessGroupId_deveRetornar403ENaoSalvar() throws Exception {
            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo Usuário",
                        "accessGroupId": 1,
                        "businessUnitIds": [5],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isForbidden());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(authorities = "PERM_WRITE_telecom.users")
        void createUser_naoAdminComRoleAdmin_deveRetornar403ENaoSalvar() throws Exception {
            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo Usuário",
                        "role": "ADMIN",
                        "businessUnitIds": [5],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isForbidden());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_usernameJaExiste_deveRetornar400() throws Exception {
            when(userRepo.findByUsername("kaio")).thenReturn(Optional.of(baseUser().build()));

            String body =
                    """
                    {
                        "username": "kaio",
                        "password": "senha123",
                        "displayName": "Duplicado",
                        "businessUnitIds": [5],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(org.hamcrest.Matchers.containsString("kaio")));

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_businessUnitIdInexistente_deveRetornar400() throws Exception {
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(businessUnitRepo.findAllById(List.of(999))).thenReturn(List.of());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo",
                        "businessUnitIds": [999],
                        "accessIndeterminate": true
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(
                                            org.hamcrest.Matchers.containsString(
                                                    "Unidades de Negócio")));

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_semBusinessUnit_deveRetornar400PorBeanValidation() throws Exception {
            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo",
                        "businessUnitIds": []
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_indeterminadoComData_deveRetornar400() throws Exception {
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo",
                        "businessUnitIds": [5],
                        "accessIndeterminate": true,
                        "accessExpiresAt": "%s"
                    }
                    """
                            .formatted(LocalDate.now().plusDays(10));

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(
                                            org.hamcrest.Matchers.containsString(
                                                    "indeterminado não pode ter data")));

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_semIndeterminadoESemData_deveRetornar400() throws Exception {
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo",
                        "businessUnitIds": [5],
                        "accessIndeterminate": false
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(
                                            org.hamcrest.Matchers.containsString(
                                                    "Informe a data de expiração")));

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_dataNoPassado_deveRetornar400() throws Exception {
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo",
                        "businessUnitIds": [5],
                        "accessIndeterminate": false,
                        "accessExpiresAt": "%s"
                    }
                    """
                            .formatted(LocalDate.now().minusDays(1));

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(org.hamcrest.Matchers.containsString("passado")));

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_dataAlemDe60Dias_deveRetornar400() throws Exception {
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo",
                        "businessUnitIds": [5],
                        "accessIndeterminate": false,
                        "accessExpiresAt": "%s"
                    }
                    """
                            .formatted(LocalDate.now().plusDays(61));

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(org.hamcrest.Matchers.containsString("60 dias")));

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createUser_dataValidaDentroDoLimite_deveSerAceita() throws Exception {
            mockGrupos();
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(userRepo.findNextExtension(9001)).thenReturn(9002);
            when(businessUnitRepo.findAllById(List.of(5))).thenReturn(List.of(bu(5)));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Novo",
                        "businessUnitIds": [5],
                        "accessIndeterminate": false,
                        "accessExpiresAt": "%s"
                    }
                    """
                            .formatted(LocalDate.now().plusDays(60));

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(authorities = "PERM_WRITE_telecom.users")
        void createUser_comQueueMemberships_semPermissaoDeFilas_deveRetornar403() throws Exception {
            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Usuário Novo",
                        "role": "USER",
                        "businessUnitIds": [5],
                        "accessIndeterminate": true,
                        "callCenterAgent": true,
                        "queueMemberships": [{"queueId": 1, "priority": 0}]
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isForbidden());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(authorities = {"PERM_WRITE_telecom.users", "PERM_WRITE_callcenter.filas"})
        void createUser_comQueueMemberships_comPermissaoDeFilas_deveProsseguir() throws Exception {
            mockGrupos();
            when(userRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(userRepo.findNextExtension(9001)).thenReturn(9002);
            when(businessUnitRepo.findAllById(List.of(5))).thenReturn(List.of(bu(5)));
            when(userRepo.save(any(AppUser.class)))
                    .thenReturn(baseUser().id(2).username("novo").extension(9002).accessIndeterminate(true).build());
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body =
                    """
                    {
                        "username": "novo",
                        "password": "senha123",
                        "displayName": "Usuário Novo",
                        "role": "USER",
                        "businessUnitIds": [5],
                        "accessIndeterminate": true,
                        "callCenterAgent": true,
                        "queueMemberships": [{"queueId": 1, "priority": 0}]
                    }
                    """;

            mockMvc.perform(post("/api/v1/users").contentType("application/json").content(body))
                    .andExpect(status().isCreated());

            verify(agentProvisioningService).provisionForUser(eq(2), eq("Kaio Correa"), eq(5), any());
        }
    }

    // =========================================================================
    // PUT — atualização
    // =========================================================================

    @Nested
    class Atualizacao {

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_inexistente_deveRetornar404() throws Exception {
            when(userRepo.findById(99)).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/v1/users/99").contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_businessUnitIdsVazio_deveRetornar400() throws Exception {
            when(userRepo.findById(1)).thenReturn(Optional.of(baseUser().build()));

            String body = """
                    { "businessUnitIds": [] }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(
                                            org.hamcrest.Matchers.containsString(
                                                    "ao menos uma BU")));

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_businessUnitIdInexistente_deveRetornar400() throws Exception {
            when(userRepo.findById(1)).thenReturn(Optional.of(baseUser().build()));
            when(businessUnitRepo.findAllById(List.of(999))).thenReturn(List.of());

            String body =
                    """
                    { "businessUnitIds": [999] }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_apenasNomeEAtivo_deveAtualizarSomenteOsCamposInformados() throws Exception {
            AppUser existente = baseUser().build();
            when(userRepo.findById(1)).thenReturn(Optional.of(existente));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body =
                    """
                    { "displayName": "Novo Nome", "isActive": false }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("Novo Nome"))
                    .andExpect(jsonPath("$.isActive").value(false))
                    // Senha não foi enviada — hash original deve permanecer intocado (não há
                    // como asserir isso via resposta, já que a senha nunca é serializada; a
                    // garantia vem do fluxo do controller: só reescreve se req.password() não
                    // estiver em branco).
                    .andExpect(jsonPath("$.extensionPassword").doesNotExist());

            verify(auditService).log(any(), eq("USER_UPDATE"), contains("Novo Nome"), eq(true));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_senhaEmBranco_naoDeveAlterarHashExistente() throws Exception {
            AppUser existente = baseUser().build();
            String hashOriginal = existente.getPasswordHash();
            when(userRepo.findById(1)).thenReturn(Optional.of(existente));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body = """
                    { "password": "   " }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getPasswordHash()).isEqualTo(hashOriginal);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_role_deveReatribuirGrupoDeAcesso() throws Exception {
            mockGrupos();
            AppUser existente = baseUser().build(); // role=USER, grupo=2
            when(userRepo.findById(1)).thenReturn(Optional.of(existente));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body = """
                    { "role": "ADMIN" }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getAccessGroup().getId()).isEqualTo(1);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_comAccessGroupIdCustomizado_devePrevalecerSobreRole() throws Exception {
            when(accessGroupRepo.findById(7))
                    .thenReturn(Optional.of(group(7, "Supervisores")));
            AppUser existente = baseUser().build(); // role=USER, grupo=2
            when(userRepo.findById(1)).thenReturn(Optional.of(existente));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            String body = """
                    { "accessGroupId": 7 }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessGroupId").value(7))
                    .andExpect(jsonPath("$.accessGroupName").value("Supervisores"));

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getAccessGroup().getId()).isEqualTo(7);
            // role legado não deve ter sido alterado, já que só accessGroupId foi enviado
            assertThat(captor.getValue().getRole()).isEqualTo("USER");
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_accessGroupIdInexistente_deveRetornar400ENaoSalvar() throws Exception {
            when(accessGroupRepo.findById(999)).thenReturn(Optional.empty());
            when(userRepo.findById(1)).thenReturn(Optional.of(baseUser().build()));

            String body = """
                    { "accessGroupId": 999 }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(authorities = "PERM_WRITE_telecom.users")
        void updateUser_naoAdminComAccessGroupId_deveRetornar403ENaoSalvar() throws Exception {
            String body = """
                    { "accessGroupId": 1 }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isForbidden());

            verify(userRepo, never()).findById(anyInt());
            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(authorities = "PERM_WRITE_telecom.users")
        void updateUser_naoAdminComRoleAdmin_deveRetornar403ENaoSalvar() throws Exception {
            String body = """
                    { "role": "ADMIN" }
                    """;

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isForbidden());

            verify(userRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void updateUser_janelaDeAcessoInvalida_deveRetornar400ENaoSalvar() throws Exception {
            when(userRepo.findById(1)).thenReturn(Optional.of(baseUser().build()));

            String body =
                    """
                    { "accessIndeterminate": false, "accessExpiresAt": "%s" }
                    """
                            .formatted(LocalDate.now().minusDays(1));

            mockMvc.perform(put("/api/v1/users/1").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());

            verify(userRepo, never()).save(any());
        }
    }

    // =========================================================================
    // POST /{id}/totp/reset — reset de MFA pelo admin
    // =========================================================================

    @Nested
    class ResetTotp {

        @Test
        @WithMockUser(roles = "ADMIN")
        void resetTotp_existente_deveLimparSegredoEDesativarMfa() throws Exception {
            AppUser existente = baseUser().totpEnabled(true).totpSecret("SEGREDOBASE32").build();
            when(userRepo.findById(1)).thenReturn(Optional.of(existente));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(post("/api/v1/users/1/totp/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totpEnabled").value(false));

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getTotpSecret()).isNull();
            assertThat(captor.getValue().getTotpEnabled()).isFalse();

            verify(auditService).log(any(), eq("USER_TOTP_RESET"), contains("kaio"), eq(true));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void resetTotp_inexistente_deveRetornar404() throws Exception {
            when(userRepo.findById(99)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/v1/users/99/totp/reset")).andExpect(status().isNotFound());

            verify(userRepo, never()).save(any());
        }
    }

    // =========================================================================
    // DELETE — desativação (soft delete)
    // =========================================================================

    @Nested
    class Desativacao {

        @Test
        @WithMockUser(roles = "ADMIN")
        void deactivateUser_existente_deveDesativarERetornar204() throws Exception {
            AppUser existente = baseUser().build();
            when(userRepo.findById(1)).thenReturn(Optional.of(existente));
            when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(delete("/api/v1/users/1")).andExpect(status().isNoContent());

            org.mockito.ArgumentCaptor<AppUser> captor =
                    org.mockito.ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(captor.capture());
            assertThat(captor.getValue().getIsActive()).isFalse();

            verify(auditService).log(any(), eq("USER_DELETE"), contains("kaio"), eq(true));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void deactivateUser_inexistente_deveRetornar404() throws Exception {
            when(userRepo.findById(99)).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/v1/users/99")).andExpect(status().isNotFound());

            verify(userRepo, never()).save(any());
        }
    }
}
