package com.asteriskia.domain.masterdata;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OperadoraControllerTest — teste de caracterização (fase 10 da refatoração). Cobre o CRUD de
 * Operadoras extraído de MasterDataController, incluindo o zeramento defensivo do id no create.
 */
@WebMvcTest(OperadoraController.class)
@AutoConfigureMockMvc(addFilters = false)
class OperadoraControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private OperadoraRepository operadoraRepo;
    @MockBean private AuditService auditService;
    @MockBean private JwtService jwtService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void listOperadoras_semFiltro_devolveTodas() throws Exception {
        when(operadoraRepo.findAll())
                .thenReturn(List.of(Operadora.builder().id(1).nome("Vivo").build()));

        mockMvc.perform(get("/api/v1/operadoras"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Vivo"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createOperadora_zeraIdRecebidoAntesDeSalvar() throws Exception {
        Operadora input = Operadora.builder().id(999).nome("Vivo").build();
        Operadora saved = Operadora.builder().id(1).nome("Vivo").build();
        when(operadoraRepo.save(any())).thenReturn(saved);

        mockMvc.perform(
                        post("/api/v1/operadoras")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteOperadora_removeERegistraAuditoria() throws Exception {
        mockMvc.perform(delete("/api/v1/operadoras/1")).andExpect(status().isNoContent());

        verify(operadoraRepo).deleteById(1);
        verify(auditService)
                .log(
                        any(),
                        org.mockito.ArgumentMatchers.eq("MASTERDATA_DELETE"),
                        any(),
                        org.mockito.ArgumentMatchers.eq(true));
    }
}
