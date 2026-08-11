package com.asteriskia.domain.masterdata;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * ClientControllerTest — teste de caracterização (fase 10 da refatoração). Cobre o CRUD de
 * Clientes, o filtro de escopo por BU e os endpoints de vínculo N:N (operações, business-units)
 * extraídos de MasterDataController.
 */
@WebMvcTest(ClientController.class)
@AutoConfigureMockMvc(addFilters = false)
class ClientControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ClientRepository clientRepo;
    @MockBean private OperationRepository opRepo;
    @MockBean private BusinessUnitRepository buRepo;
    @MockBean private AuditService auditService;
    @MockBean private JwtService jwtService;

    private static final BusinessUnit BU = BusinessUnit.builder().id(1).name("Matriz").build();

    @Test
    @WithMockUser(roles = "ADMIN")
    void listClients_admin_naoAplicaFiltroDeBU() throws Exception {
        Client c = Client.builder().id(1).name("Cliente A").businessUnits(Set.of()).build();
        when(clientRepo.findAll()).thenReturn(List.of(c));

        mockMvc.perform(get("/api/v1/clients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getClientOperations_clienteInexistente_devolve404() throws Exception {
        when(clientRepo.findById(99)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/clients/99/operations"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void syncClientBusinessUnits_clienteInexistente_devolve404() throws Exception {
        when(clientRepo.findById(99)).thenReturn(Optional.empty());

        mockMvc.perform(
                        put("/api/v1/clients/99/business-units")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[1,2]"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void syncClientBusinessUnits_idInexistente_devolve400() throws Exception {
        Client client = Client.builder().id(1).name("Cliente A").build();
        when(clientRepo.findById(1)).thenReturn(Optional.of(client));
        when(buRepo.findAllById(any())).thenReturn(List.of());

        mockMvc.perform(
                        put("/api/v1/clients/1/business-units")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[1,2]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void syncClientBusinessUnits_sucesso_atualizaEDevolveCliente() throws Exception {
        Client client = Client.builder().id(1).name("Cliente A").build();
        when(clientRepo.findById(1)).thenReturn(Optional.of(client));
        when(buRepo.findAllById(any())).thenReturn(List.of(BU));
        when(clientRepo.save(any())).thenReturn(client);

        mockMvc.perform(
                        put("/api/v1/clients/1/business-units")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[1]"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addOperation_vinculaOperacaoAoCliente() throws Exception {
        Client client = Client.builder().id(1).name("Cliente A").build();
        Operation op = Operation.builder().id(2).name("Op X").build();
        when(clientRepo.findById(1)).thenReturn(Optional.of(client));
        when(opRepo.findById(2)).thenReturn(Optional.of(op));

        mockMvc.perform(post("/api/v1/clients/1/operations/2")).andExpect(status().isNoContent());

        verify(clientRepo).save(client);
    }
}
