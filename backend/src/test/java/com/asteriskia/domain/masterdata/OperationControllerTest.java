package com.asteriskia.domain.masterdata;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.audit.AuditService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OperationControllerTest — teste de caracterização (fase 10 da refatoração). Cobre o CRUD de
 * Operações, o filtro de escopo por BU e os endpoints de sincronização N:N (business-units,
 * clients) extraídos de MasterDataController.
 */
@WebMvcTest(OperationController.class)
@AutoConfigureMockMvc(addFilters = false)
class OperationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private OperationRepository opRepo;
    @MockBean private ClientRepository clientRepo;
    @MockBean private BusinessUnitRepository buRepo;
    @MockBean private AuditService auditService;
    @MockBean private JwtService jwtService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void listOps_admin_naoAplicaFiltroDeBU() throws Exception {
        Operation op = Operation.builder().id(1).name("Op X").businessUnits(Set.of()).build();
        when(opRepo.findAll()).thenReturn(List.of(op));

        mockMvc.perform(get("/api/v1/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void syncOperationClients_idDeClienteInexistente_devolve400() throws Exception {
        Operation op = Operation.builder().id(1).name("Op X").build();
        when(opRepo.findById(1)).thenReturn(Optional.of(op));
        when(clientRepo.findAllById(any())).thenReturn(List.of());

        mockMvc.perform(
                        put("/api/v1/operations/1/clients")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[1]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void syncOperationBusinessUnits_operacaoInexistente_devolve404() throws Exception {
        when(opRepo.findById(99)).thenReturn(Optional.empty());

        mockMvc.perform(
                        put("/api/v1/operations/99/business-units")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[1]"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void syncOperationBusinessUnits_sucesso_atualizaEDevolveOperacao() throws Exception {
        Operation op = Operation.builder().id(1).name("Op X").build();
        BusinessUnit bu = BusinessUnit.builder().id(1).name("Matriz").build();
        when(opRepo.findById(1)).thenReturn(Optional.of(op));
        when(buRepo.findAllById(any())).thenReturn(List.of(bu));
        when(opRepo.save(any())).thenReturn(op);

        mockMvc.perform(
                        put("/api/v1/operations/1/business-units")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[1]"))
                .andExpect(status().isOk());
    }
}
