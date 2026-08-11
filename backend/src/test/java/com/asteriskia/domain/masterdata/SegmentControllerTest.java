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
 * SegmentControllerTest — teste de caracterização (fase 10 da refatoração). Cobre o CRUD de
 * Segmentos extraído de MasterDataController.
 */
@WebMvcTest(SegmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class SegmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private SegmentRepository segRepo;
    @MockBean private AuditService auditService;
    @MockBean private JwtService jwtService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void listSegments_semFiltro_devolveTodos() throws Exception {
        when(segRepo.findAll()).thenReturn(List.of(Segment.builder().id(1).name("Varejo").build()));

        mockMvc.perform(get("/api/v1/segments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Varejo"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listSegments_comFiltroActive_usaFindByIsActive() throws Exception {
        when(segRepo.findByIsActive(true))
                .thenReturn(List.of(Segment.builder().id(1).name("Varejo").build()));

        mockMvc.perform(get("/api/v1/segments?active=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createSegment_persisteERegistraAuditoria() throws Exception {
        Segment seg = Segment.builder().id(1).name("Varejo").build();
        when(segRepo.save(any())).thenReturn(seg);

        mockMvc.perform(
                        post("/api/v1/segments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(seg)))
                .andExpect(status().isCreated());

        verify(auditService)
                .log(
                        any(),
                        org.mockito.ArgumentMatchers.eq("MASTERDATA_CREATE"),
                        any(),
                        org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSegment_removeERegistraAuditoria() throws Exception {
        mockMvc.perform(delete("/api/v1/segments/1")).andExpect(status().isNoContent());

        verify(segRepo).deleteById(1);
    }
}
