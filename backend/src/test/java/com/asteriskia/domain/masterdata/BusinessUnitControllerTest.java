package com.asteriskia.domain.masterdata;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * BusinessUnitControllerTest — teste de caracterização (fase 10 da refatoração). Cobre o CRUD de
 * Unidades de Negócio extraído de MasterDataController.
 */
@WebMvcTest(BusinessUnitController.class)
@AutoConfigureMockMvc(addFilters = false)
class BusinessUnitControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private BusinessUnitRepository buRepo;
    @MockBean private AuditService auditService;
    @MockBean private JwtService jwtService;

    private static final BusinessUnit BU =
            BusinessUnit.builder().id(1).name("Matriz").isActive(true).build();

    @Test
    @WithMockUser(roles = "ADMIN")
    void listBUs_semFiltro_devolveTodasAsBUs() throws Exception {
        when(buRepo.findAll()).thenReturn(List.of(BU));

        mockMvc.perform(get("/api/v1/business-units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Matriz"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listBUs_comFiltroActive_usaFindByIsActive() throws Exception {
        when(buRepo.findByIsActive(true)).thenReturn(List.of(BU));

        mockMvc.perform(get("/api/v1/business-units?active=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createBU_persisteERegistraAuditoria() throws Exception {
        when(buRepo.save(any())).thenReturn(BU);

        mockMvc.perform(
                        post("/api/v1/business-units")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(BU)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Matriz"));

        verify(auditService)
                .log(
                        any(),
                        eq("MASTERDATA_CREATE"),
                        org.mockito.ArgumentMatchers.contains("Matriz"),
                        eq(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateBU_setaIdDoPathEPersiste() throws Exception {
        when(buRepo.save(any())).thenReturn(BU);

        mockMvc.perform(
                        put("/api/v1/business-units/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(BU)))
                .andExpect(status().isOk());

        verify(auditService).log(any(), eq("MASTERDATA_UPDATE"), any(), eq(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteBU_removeERegistraAuditoria() throws Exception {
        mockMvc.perform(delete("/api/v1/business-units/1")).andExpect(status().isNoContent());

        verify(buRepo).deleteById(1);
        verify(auditService).log(any(), eq("MASTERDATA_DELETE"), any(), eq(true));
    }
}
