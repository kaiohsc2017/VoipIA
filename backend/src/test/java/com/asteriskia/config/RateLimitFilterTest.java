package com.asteriskia.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.asteriskia.domain.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** RateLimitFilterTest — Testa bloqueio por IP após exceder limite de tentativas. */
class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        AuditService auditServiceMock = mock(AuditService.class);
        ObjectMapper objectMapperMock = new ObjectMapper();
        filter = new RateLimitFilter(auditServiceMock, objectMapperMock);
    }

    @Test
    void requisicaoNaoRestrita_devePassar() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        // 200 (default do MockHttpServletResponse)
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void loginAteLimit_devePassar() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = req("/api/v1/auth/login", "10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, resp, chain);
            assertThat(resp.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void loginAcimaDoLimite_deveRetornar429() throws Exception {
        String ip = "192.168.1.99";

        // Consome todas as tentativas (10)
        for (int i = 0; i < 10; i++) {
            filter.doFilter(
                    req("/api/v1/auth/login", ip),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        // 11ª tentativa deve ser bloqueada
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req("/api/v1/auth/login", ip), resp, new MockFilterChain());

        assertThat(resp.getStatus()).isEqualTo(429);
    }

    @Test
    void ipsDistintos_naoDevemInterfenir() throws Exception {
        String ip1 = "10.10.10.1";
        String ip2 = "10.10.10.2";

        // Bloqueia ip1
        for (int i = 0; i <= 10; i++) {
            filter.doFilter(
                    req("/api/v1/auth/login", ip1),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        // ip2 deve passar normalmente
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req("/api/v1/auth/login", ip2), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isNotEqualTo(429);
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private MockHttpServletRequest req(String path, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        // RateLimitFilter decide pelo servletPath (não pelo requestURI). O construtor
        // de MockHttpServletRequest não infere um a partir do outro — sem isto, o
        // filtro nunca reconhece o endpoint como protegido e o rate limit não dispara.
        req.setServletPath(path);
        req.setRemoteAddr(ip);
        return req;
    }
}
