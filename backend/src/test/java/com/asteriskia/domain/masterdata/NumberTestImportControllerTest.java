package com.asteriskia.domain.masterdata;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.audit.AuditService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NumberTestImportControllerTest — teste de caracterização (fase 10 da refatoração). Cobre o
 * endpoint de import CSV extraído de MasterDataController.
 */
@WebMvcTest(NumberTestImportController.class)
@AutoConfigureMockMvc(addFilters = false)
class NumberTestImportControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private NumberTestImportService numberTestImportService;
    @MockBean private AuditService auditService;
    @MockBean private JwtService jwtService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void importNumberTests_arquivoVazio_devolve400SemChamarService() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "vazio.csv", "text/csv", new byte[0]);

        mockMvc.perform(multipart("/api/v1/number-tests/import").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Arquivo vazio."));

        verify(numberTestImportService, never()).importFromCsv(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void importNumberTests_sucesso_devolveContagemDeImportadosEErros() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "import.csv", "text/csv", "numero;bu\n123;Matriz\n".getBytes());
        when(numberTestImportService.importFromCsv(any()))
                .thenReturn(new NumberTestImportService.ImportResult(List.of(), List.of()));

        mockMvc.perform(multipart("/api/v1/number-tests/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importados").value(0))
                .andExpect(jsonPath("$.erros").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void importNumberTests_erroDeValidacao_devolve400() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "import.csv", "text/csv", "x".getBytes());
        when(numberTestImportService.importFromCsv(any()))
                .thenThrow(new IllegalArgumentException("Arquivo sem cabeçalho."));

        mockMvc.perform(multipart("/api/v1/number-tests/import").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Arquivo sem cabeçalho."));
    }
}
