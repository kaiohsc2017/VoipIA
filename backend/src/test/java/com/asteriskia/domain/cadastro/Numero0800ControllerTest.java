package com.asteriskia.domain.cadastro;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.asteriskia.config.JwtService;
import com.asteriskia.config.RateLimitFilter;
import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import com.asteriskia.domain.masterdata.Operadora;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Numero0800ControllerTest — CRUD do cadastro "Números 0800", escopo por BU e sincronização de
 * Unidades de Negócio. Extraído de CadastroControllerTest (fase 14 da refatoração, junto com a
 * divisão de CadastroController em Numero0800Controller + LinhaController).
 *
 * <p>Segue o padrão de {@code AuthControllerTest}: {@code @WebMvcTest} com
 * {@code @AutoConfigureMockMvc(addFilters = false)} — desliga a {@code FilterChainProxy} do Spring
 * Security (RBAC granular já teria bloqueado a maioria destes cenários por permissão, não é o que
 * estes testes querem exercitar). {@code BusinessUnitContext} lê o {@code SecurityContextHolder}
 * diretamente e independe dos filtros — {@code @WithMockUser(authorities=...)} já popula esse
 * contexto antes da requisição, então o escopo por BU continua sendo exercitado de verdade.
 */
@WebMvcTest(Numero0800Controller.class)
@AutoConfigureMockMvc(addFilters = false)
class Numero0800ControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private Numero0800Repository numero0800Repo;

    @MockBean private BusinessUnitRepository buRepo;

    @MockBean private CadastroExcelService excelService;

    @MockBean private AuditService auditService;

    @MockBean private JwtService jwtService;

    @MockBean private RateLimitFilter rateLimitFilter;

    private static BusinessUnit bu(Integer id) {
        return BusinessUnit.builder().id(id).name("BU " + id).isActive(true).build();
    }

    private static Operadora operadora(String nome) {
        return Operadora.builder().id(1).nome(nome).isActive(true).build();
    }

    // =========================================================================
    // CRUD básico
    // =========================================================================

    @Nested
    class Numero0800Crud {

        @Test
        @WithMockUser(roles = "ADMIN")
        void list_deveRetornarTodosOsNumeros() throws Exception {
            Numero0800 n1 =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .build();
            Numero0800 n2 =
                    Numero0800.builder()
                            .id(2)
                            .operadora(operadora("Claro"))
                            .numero("0800222")
                            .build();
            when(numero0800Repo.findAll()).thenReturn(List.of(n1, n2));

            mockMvc.perform(get("/api/v1/numeros-0800"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void create_deveRetornar201ComEntidadeSalva() throws Exception {
            Numero0800 payload =
                    Numero0800.builder().operadora(operadora("Vivo")).numero("0800111").build();
            Numero0800 saved =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .build();
            when(numero0800Repo.save(any(Numero0800.class))).thenReturn(saved);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(
                            post("/api/v1/numeros-0800")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.operadora.nome").value("Vivo"));

            verify(numero0800Repo).save(any(Numero0800.class));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void update_deveRetornar200ComEntidadeAtualizada() throws Exception {
            Numero0800 payload =
                    Numero0800.builder()
                            .operadora(operadora("Vivo Atualizado"))
                            .numero("0800111")
                            .build();
            Numero0800 saved =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo Atualizado"))
                            .numero("0800111")
                            .build();
            when(numero0800Repo.save(any(Numero0800.class))).thenReturn(saved);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(
                            put("/api/v1/numeros-0800/1")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operadora.nome").value("Vivo Atualizado"));

            verify(numero0800Repo).save(argThat(n -> n.getId().equals(1)));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void delete_deveRetornar204() throws Exception {
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(delete("/api/v1/numeros-0800/1")).andExpect(status().isNoContent());

            verify(numero0800Repo).deleteById(1);
        }
    }

    // =========================================================================
    // Escopo por BU — usuário restrito só vê itens da sua BU ou sem BU; ADMIN
    // não é filtrado
    // =========================================================================

    @Nested
    class EscopoPorBusinessUnit {

        @Test
        @WithMockUser(
                username = "restrito",
                authorities = {"BU_5"})
        void listNumeros0800_usuarioRestrito_veApenasSuaBuOuSemBu() throws Exception {
            Numero0800 daSuaBu =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .businessUnits(Set.of(bu(5)))
                            .build();
            Numero0800 deOutraBu =
                    Numero0800.builder()
                            .id(2)
                            .operadora(operadora("Claro"))
                            .numero("0800222")
                            .businessUnits(Set.of(bu(9)))
                            .build();
            Numero0800 semBu =
                    Numero0800.builder()
                            .id(3)
                            .operadora(operadora("Tim"))
                            .numero("0800333")
                            .businessUnits(Set.of())
                            .build();
            when(numero0800Repo.findAll()).thenReturn(List.of(daSuaBu, deOutraBu, semBu));

            mockMvc.perform(get("/api/v1/numeros-0800"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[?(@.id==1)]").exists())
                    .andExpect(jsonPath("$[?(@.id==2)]").doesNotExist())
                    .andExpect(jsonPath("$[?(@.id==3)]").exists());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void listNumeros0800_admin_veTudoSemFiltro() throws Exception {
            Numero0800 daBu5 =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .businessUnits(Set.of(bu(5)))
                            .build();
            Numero0800 daBu9 =
                    Numero0800.builder()
                            .id(2)
                            .operadora(operadora("Claro"))
                            .numero("0800222")
                            .businessUnits(Set.of(bu(9)))
                            .build();
            when(numero0800Repo.findAll()).thenReturn(List.of(daBu5, daBu9));

            mockMvc.perform(get("/api/v1/numeros-0800"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }
    }

    // =========================================================================
    // PUT .../{id}/business-units — sincronização de BUs
    // =========================================================================

    @Nested
    class SincronizacaoDeBusinessUnits {

        @Test
        @WithMockUser(roles = "ADMIN")
        void syncNumero0800BusinessUnits_idInexistente_deveRetornar404() throws Exception {
            when(numero0800Repo.findById(99)).thenReturn(Optional.empty());

            mockMvc.perform(
                            put("/api/v1/numeros-0800/99/business-units")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(List.of(1, 2))))
                    .andExpect(status().isNotFound());

            verify(numero0800Repo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void syncNumero0800BusinessUnits_buIdInexistente_deveRetornar400() throws Exception {
            Numero0800 existente =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .build();
            when(numero0800Repo.findById(1)).thenReturn(Optional.of(existente));
            when(buRepo.findAllById(anyList())).thenReturn(List.of(bu(5))); // só 1 de 2 ids existe

            mockMvc.perform(
                            put("/api/v1/numeros-0800/1/business-units")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(List.of(5, 999))))
                    .andExpect(status().isBadRequest());

            verify(numero0800Repo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void syncNumero0800BusinessUnits_sucesso_deveRetornar200ComEntidadeAtualizada()
                throws Exception {
            Numero0800 existente =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .build();
            Numero0800 salvo =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .businessUnits(Set.of(bu(5), bu(7)))
                            .build();
            when(numero0800Repo.findById(1)).thenReturn(Optional.of(existente));
            when(buRepo.findAllById(anyList())).thenReturn(List.of(bu(5), bu(7)));
            when(numero0800Repo.save(any(Numero0800.class))).thenReturn(salvo);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(
                            put("/api/v1/numeros-0800/1/business-units")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(List.of(5, 7))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.businessUnits.length()").value(2));
        }
    }

    // =========================================================================
    // Validação Bean — @NotBlank / @Size
    // =========================================================================

    @Nested
    class Validacao {

        @Test
        @WithMockUser(roles = "ADMIN")
        void createNumero0800_operadoraNula_deveRetornar400() throws Exception {
            Numero0800 payload = Numero0800.builder().numero("0800111").build();

            mockMvc.perform(
                            post("/api/v1/numeros-0800")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest());

            verify(numero0800Repo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createNumero0800_maisDeCincoRegenerados_deveRetornar400() throws Exception {
            List<Numero0800Regenerado> seis =
                    List.of(
                            Numero0800Regenerado.builder().ordem(1).build(),
                            Numero0800Regenerado.builder().ordem(2).build(),
                            Numero0800Regenerado.builder().ordem(3).build(),
                            Numero0800Regenerado.builder().ordem(4).build(),
                            Numero0800Regenerado.builder().ordem(5).build(),
                            Numero0800Regenerado.builder().ordem(1).build());
            Numero0800 payload =
                    Numero0800.builder()
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .regenerados(seis)
                            .build();

            mockMvc.perform(
                            post("/api/v1/numeros-0800")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest());

            verify(numero0800Repo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createNumero0800_atéCincoRegenerados_deveSerAceito() throws Exception {
            List<Numero0800Regenerado> cinco =
                    List.of(
                            Numero0800Regenerado.builder().ordem(1).build(),
                            Numero0800Regenerado.builder().ordem(2).build(),
                            Numero0800Regenerado.builder().ordem(3).build(),
                            Numero0800Regenerado.builder().ordem(4).build(),
                            Numero0800Regenerado.builder().ordem(5).build());
            Numero0800 payload =
                    Numero0800.builder()
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .regenerados(cinco)
                            .build();
            Numero0800 saved =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .regenerados(cinco)
                            .build();
            when(numero0800Repo.save(any(Numero0800.class))).thenReturn(saved);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(
                            post("/api/v1/numeros-0800")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isCreated());

            verify(numero0800Repo).save(any(Numero0800.class));
        }
    }

    // =========================================================================
    // Exportação, modelo e importação em lote (XLSX)
    // =========================================================================

    @Nested
    class ExportacaoEImportacao {

        @Test
        @WithMockUser(roles = "ADMIN")
        void exportNumeros0800_deveRetornarXlsx() throws Exception {
            when(numero0800Repo.findAll()).thenReturn(List.of());
            when(excelService.exportNumeros0800(anyList())).thenReturn(new byte[] {1, 2, 3});

            mockMvc.perform(get("/api/v1/numeros-0800/export"))
                    .andExpect(status().isOk())
                    .andExpect(
                            header().string(
                                            "Content-Type",
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void templateNumeros0800_deveRetornarXlsx() throws Exception {
            when(excelService.templateNumeros0800()).thenReturn(new byte[] {1, 2, 3});

            mockMvc.perform(get("/api/v1/numeros-0800/template")).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void importNumeros0800_arquivoVazio_deveRetornar400() throws Exception {
            MockMultipartFile file =
                    new MockMultipartFile(
                            "file", "vazio.xlsx", "application/octet-stream", new byte[0]);

            mockMvc.perform(multipart("/api/v1/numeros-0800/import").file(file))
                    .andExpect(status().isBadRequest());

            verify(numero0800Repo, never()).saveAll(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void importNumeros0800_sucesso_deveRetornarResumo() throws Exception {
            MockMultipartFile file =
                    new MockMultipartFile(
                            "file", "dados.xlsx", "application/octet-stream", new byte[] {1});
            Numero0800 n =
                    Numero0800.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .numero("0800111")
                            .build();
            when(excelService.importNumeros0800(any()))
                    .thenReturn(new CadastroExcelService.ImportResult<>(List.of(n), List.of()));
            when(numero0800Repo.saveAll(anyList())).thenReturn(List.of(n));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(multipart("/api/v1/numeros-0800/import").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.importados").value(1))
                    .andExpect(jsonPath("$.erros").value(0));
        }
    }
}
