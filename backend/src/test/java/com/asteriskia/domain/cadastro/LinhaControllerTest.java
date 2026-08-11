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
 * LinhaControllerTest — CRUD do cadastro "Linhas", escopo por BU e sincronização de Unidades de
 * Negócio. Extraído de CadastroControllerTest (fase 14 da refatoração, junto com a divisão de
 * CadastroController em Numero0800Controller + LinhaController).
 *
 * <p>Segue o padrão de {@code AuthControllerTest}: {@code @WebMvcTest} com
 * {@code @AutoConfigureMockMvc(addFilters = false)} — desliga a {@code FilterChainProxy} do Spring
 * Security. {@code BusinessUnitContext} lê o {@code SecurityContextHolder} diretamente e independe
 * dos filtros — {@code @WithMockUser(authorities=...)} já popula esse contexto antes da requisição,
 * então o escopo por BU continua sendo exercitado de verdade.
 */
@WebMvcTest(LinhaController.class)
@AutoConfigureMockMvc(addFilters = false)
class LinhaControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private LinhaRepository linhaRepo;

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
    class LinhaCrud {

        @Test
        @WithMockUser(roles = "ADMIN")
        void list_deveRetornarTodasAsLinhas() throws Exception {
            Linha l1 = Linha.builder().id(1).operadora(operadora("Vivo")).build();
            Linha l2 = Linha.builder().id(2).operadora(operadora("Claro")).build();
            when(linhaRepo.findAll()).thenReturn(List.of(l1, l2));

            mockMvc.perform(get("/api/v1/linhas"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void create_deveRetornar201ComEntidadeSalva() throws Exception {
            Linha payload = Linha.builder().operadora(operadora("Vivo")).build();
            Linha saved = Linha.builder().id(1).operadora(operadora("Vivo")).build();
            when(linhaRepo.save(any(Linha.class))).thenReturn(saved);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(
                            post("/api/v1/linhas")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.operadora.nome").value("Vivo"));

            verify(linhaRepo).save(any(Linha.class));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void update_deveRetornar200ComEntidadeAtualizada() throws Exception {
            Linha payload = Linha.builder().operadora(operadora("Vivo Atualizada")).build();
            Linha saved = Linha.builder().id(1).operadora(operadora("Vivo Atualizada")).build();
            when(linhaRepo.save(any(Linha.class))).thenReturn(saved);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(
                            put("/api/v1/linhas/1")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operadora.nome").value("Vivo Atualizada"));

            verify(linhaRepo).save(argThat(l -> l.getId().equals(1)));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void delete_deveRetornar204() throws Exception {
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(delete("/api/v1/linhas/1")).andExpect(status().isNoContent());

            verify(linhaRepo).deleteById(1);
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
        void listLinhas_usuarioRestrito_veApenasSuaBuOuSemBu() throws Exception {
            Linha daSuaBu =
                    Linha.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .businessUnits(Set.of(bu(5)))
                            .build();
            Linha deOutraBu =
                    Linha.builder()
                            .id(2)
                            .operadora(operadora("Claro"))
                            .businessUnits(Set.of(bu(9)))
                            .build();
            Linha semBu =
                    Linha.builder()
                            .id(3)
                            .operadora(operadora("Tim"))
                            .businessUnits(Set.of())
                            .build();
            when(linhaRepo.findAll()).thenReturn(List.of(daSuaBu, deOutraBu, semBu));

            mockMvc.perform(get("/api/v1/linhas"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[?(@.id==1)]").exists())
                    .andExpect(jsonPath("$[?(@.id==2)]").doesNotExist())
                    .andExpect(jsonPath("$[?(@.id==3)]").exists());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void listLinhas_admin_veTudoSemFiltro() throws Exception {
            Linha daBu5 =
                    Linha.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .businessUnits(Set.of(bu(5)))
                            .build();
            Linha daBu9 =
                    Linha.builder()
                            .id(2)
                            .operadora(operadora("Claro"))
                            .businessUnits(Set.of(bu(9)))
                            .build();
            when(linhaRepo.findAll()).thenReturn(List.of(daBu5, daBu9));

            mockMvc.perform(get("/api/v1/linhas"))
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
        void syncLinhaBusinessUnits_idInexistente_deveRetornar404() throws Exception {
            when(linhaRepo.findById(99)).thenReturn(Optional.empty());

            mockMvc.perform(
                            put("/api/v1/linhas/99/business-units")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(List.of(1, 2))))
                    .andExpect(status().isNotFound());

            verify(linhaRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void syncLinhaBusinessUnits_buIdInexistente_deveRetornar400() throws Exception {
            Linha existente = Linha.builder().id(1).operadora(operadora("Vivo")).build();
            when(linhaRepo.findById(1)).thenReturn(Optional.of(existente));
            when(buRepo.findAllById(anyList())).thenReturn(List.of(bu(5)));

            mockMvc.perform(
                            put("/api/v1/linhas/1/business-units")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(List.of(5, 999))))
                    .andExpect(status().isBadRequest());

            verify(linhaRepo, never()).save(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void syncLinhaBusinessUnits_sucesso_deveRetornar200ComEntidadeAtualizada()
                throws Exception {
            Linha existente = Linha.builder().id(1).operadora(operadora("Vivo")).build();
            Linha salva =
                    Linha.builder()
                            .id(1)
                            .operadora(operadora("Vivo"))
                            .businessUnits(Set.of(bu(5), bu(7)))
                            .build();
            when(linhaRepo.findById(1)).thenReturn(Optional.of(existente));
            when(buRepo.findAllById(anyList())).thenReturn(List.of(bu(5), bu(7)));
            when(linhaRepo.save(any(Linha.class))).thenReturn(salva);
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(
                            put("/api/v1/linhas/1/business-units")
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
        void createLinha_operadoraNula_deveRetornar400() throws Exception {
            Linha payload = Linha.builder().build();

            mockMvc.perform(
                            post("/api/v1/linhas")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest());

            verify(linhaRepo, never()).save(any());
        }
    }

    // =========================================================================
    // Exportação, modelo e importação em lote (XLSX)
    // =========================================================================

    @Nested
    class ExportacaoEImportacao {

        @Test
        @WithMockUser(roles = "ADMIN")
        void exportLinhas_deveRetornarXlsx() throws Exception {
            when(linhaRepo.findAll()).thenReturn(List.of());
            when(excelService.exportLinhas(anyList())).thenReturn(new byte[] {1, 2, 3});

            mockMvc.perform(get("/api/v1/linhas/export")).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void templateLinhas_deveRetornarXlsx() throws Exception {
            when(excelService.templateLinhas()).thenReturn(new byte[] {1, 2, 3});

            mockMvc.perform(get("/api/v1/linhas/template")).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void importLinhas_arquivoVazio_deveRetornar400() throws Exception {
            MockMultipartFile file =
                    new MockMultipartFile(
                            "file", "vazio.xlsx", "application/octet-stream", new byte[0]);

            mockMvc.perform(multipart("/api/v1/linhas/import").file(file))
                    .andExpect(status().isBadRequest());

            verify(linhaRepo, never()).saveAll(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void importLinhas_sucesso_deveRetornarResumo() throws Exception {
            MockMultipartFile file =
                    new MockMultipartFile(
                            "file", "dados.xlsx", "application/octet-stream", new byte[] {1});
            Linha l = Linha.builder().id(1).operadora(operadora("Vivo")).build();
            when(excelService.importLinhas(any()))
                    .thenReturn(new CadastroExcelService.ImportResult<>(List.of(l), List.of()));
            when(linhaRepo.saveAll(anyList())).thenReturn(List.of(l));
            doNothing().when(auditService).log(any(), any(), any(), anyBoolean());

            mockMvc.perform(multipart("/api/v1/linhas/import").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.importados").value(1))
                    .andExpect(jsonPath("$.erros").value(0));
        }
    }
}
