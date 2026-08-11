package com.asteriskia.domain.connectivity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.asteriskia.config.JwtService;
import com.asteriskia.config.RateLimitFilter;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import com.asteriskia.domain.masterdata.ClientRepository;
import com.asteriskia.domain.masterdata.OperationRepository;
import com.asteriskia.domain.masterdata.SegmentRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** ConnectivityControllerTest — Verifica que os filtros de dados mestres chegam ao repository. */
@WebMvcTest(ConnectivityController.class)
class ConnectivityControllerTest {

    @Autowired private MockMvc mockMvc;

    // ConnectivityController usa repositories inline no mesmo arquivo
    @MockBean(name = "numberTestRepository")
    private NumberTestRepository numberTestRepo;

    @MockBean private TestResultRepository testResultRepo;

    @MockBean private SimpMessagingTemplate messagingTemplate;

    @MockBean private BusinessUnitRepository busRepo;

    @MockBean private ClientRepository clientRepo;

    @MockBean private OperationRepository operationRepo;

    @MockBean private SegmentRepository segmentRepo;

    @MockBean private JwtService jwtService;

    @MockBean private RateLimitFilter rateLimitFilter;

    /**
     * RateLimitFilter é um @Component que implementa Filter — o @WebMvcTest o registra
     * automaticamente na cadeia do MockMvc. Como mock, doFilter() vira um no-op que nunca chama
     * chain.doFilter(), engolindo toda requisição antes do controller. Configura o mock como
     * pass-through.
     */
    @BeforeEach
    void passThroughRateLimitFilter() throws Exception {
        doAnswer(
                        invocation -> {
                            ServletRequest req = invocation.getArgument(0);
                            ServletResponse res = invocation.getArgument(1);
                            FilterChain chain = invocation.getArgument(2);
                            chain.doFilter(req, res);
                            return null;
                        })
                .when(rateLimitFilter)
                .doFilter(any(), any(), any());
    }

    @Test
    @WithMockUser(
            roles = "ADMIN") // ADMIN bypassa BusinessUnitContext — testa só o filtro explícito, não
    // a restrição por BU
    void listResults_semFiltros_deveChamarFindWithFiltersNulos() throws Exception {
        when(testResultRepo.findWithFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/test-results")).andExpect(status().isOk());

        verify(testResultRepo)
                .findWithFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class));
    }

    @Test
    @WithMockUser(
            roles = "ADMIN") // ADMIN bypassa BusinessUnitContext — testa só o filtro explícito, não
    // a restrição por BU
    void listResults_comBusinessUnitId_devePassarParaRepository() throws Exception {
        when(testResultRepo.findWithFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(5L),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/test-results?businessUnitId=5")).andExpect(status().isOk());

        verify(testResultRepo)
                .findWithFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(5L),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class));
    }

    @Test
    @WithMockUser(
            roles = "ADMIN") // ADMIN bypassa BusinessUnitContext — testa só o filtro explícito, não
    // a restrição por BU
    void listResults_comTodosFiltros_devePassarTodosParaRepository() throws Exception {
        when(testResultRepo.findWithFilters(
                        isNull(),
                        eq("SUCESSO"),
                        isNull(),
                        isNull(),
                        eq(1L),
                        eq(2L),
                        eq(3L),
                        eq(4L),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(
                        get(
                                "/api/v1/test-results?status=SUCESSO&businessUnitId=1&clientId=2&operationId=3&segmentId=4"))
                .andExpect(status().isOk());

        verify(testResultRepo)
                .findWithFilters(
                        isNull(),
                        eq("SUCESSO"),
                        isNull(),
                        isNull(),
                        eq(1L),
                        eq(2L),
                        eq(3L),
                        eq(4L),
                        isNull(),
                        any(Pageable.class));
    }
}
