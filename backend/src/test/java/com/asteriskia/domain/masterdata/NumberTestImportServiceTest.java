package com.asteriskia.domain.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.connectivity.NumberTest;
import com.asteriskia.domain.connectivity.NumberTestRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * NumberTestImportServiceTest — teste de caracterização (fase 5 da refatoração). Trava o
 * comportamento atual do parsing de CSV extraído de MasterDataController — o pedaço de maior risco
 * do arquivo (validação por linha, normalização de nomes, tolerância a erros parciais).
 */
class NumberTestImportServiceTest {

    @Mock private BusinessUnitRepository buRepo;
    @Mock private ClientRepository clientRepo;
    @Mock private OperationRepository opRepo;
    @Mock private SegmentRepository segRepo;
    @Mock private NumberTestRepository numberTestRepo;

    private NumberTestImportService service;

    private static final BusinessUnit BU = BusinessUnit.builder().id(1).name("Matriz").build();
    private static final Client CLIENT = Client.builder().id(1).name("Cliente A").build();
    private static final Operation OPERATION = Operation.builder().id(1).name("Op X").build();
    private static final Segment SEGMENT = Segment.builder().id(1).name("Varejo").build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NumberTestImportService(buRepo, clientRepo, opRepo, segRepo, numberTestRepo);
        when(buRepo.findAll()).thenReturn(List.of(BU));
        when(clientRepo.findAll()).thenReturn(List.of(CLIENT));
        when(opRepo.findAll()).thenReturn(List.of(OPERATION));
        when(segRepo.findAll()).thenReturn(List.of(SEGMENT));
        when(numberTestRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MultipartFile csv(String content) {
        return new MockMultipartFile(
                "file", "import.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importFromCsv_linhaValida_deveCriarNumberTestComCamposResolvidos() throws Exception {
        String content =
                "numero;bu;cliente;operacao;segmento;horario;intervalo;quantidade;ativo\n"
                        + "+5511999999999;Matriz;Cliente A;Op X;Varejo;08:00;30;5;true\n";

        var result = service.importFromCsv(csv(content));

        assertThat(result.errors()).isEmpty();
        assertThat(result.saved()).hasSize(1);
        NumberTest saved = result.saved().get(0);
        assertThat(saved.getPhoneNumber()).isEqualTo("+5511999999999");
        assertThat(saved.getBusinessUnit()).isEqualTo(BU);
        assertThat(saved.getClient()).isEqualTo(CLIENT);
        assertThat(saved.getOperation()).isEqualTo(OPERATION);
        assertThat(saved.getSegment()).isEqualTo(SEGMENT);
        assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(saved.getIntervalMinutes()).isEqualTo(30);
        assertThat(saved.getQuantity()).isEqualTo(5);
        assertThat(saved.getIsActive()).isTrue();
    }

    @Test
    void importFromCsv_buInexistente_deveGerarErroDeLinhaSemInterromperImportacao()
            throws Exception {
        String content =
                "numero;bu;cliente;operacao;segmento;horario;intervalo;quantidade;ativo\n"
                        + "+5511999999999;BU-Que-Nao-Existe;Cliente A;Op X;Varejo;08:00;30;5;true\n"
                        + "+5511888888888;Matriz;Cliente A;Op X;Varejo;09:00;30;5;true\n";

        var result = service.importFromCsv(csv(content));

        assertThat(result.saved()).hasSize(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).containsEntry("linha", 2);
        assertThat((String) result.errors().get(0).get("erro")).contains("BU não encontrada");
    }

    @Test
    void importFromCsv_semCabecalho_deveLancarIllegalArgumentException() {
        assertThatThrownBy(() -> service.importFromCsv(csv("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Arquivo sem cabeçalho.");
    }

    @Test
    void importFromCsv_ativoComValorNao_deveResolverParaInativo() throws Exception {
        String content =
                "numero;bu;cliente;operacao;segmento;horario;intervalo;quantidade;ativo\n"
                        + "+5511999999999;Matriz;Cliente A;Op X;Varejo;08:00;30;5;nao\n";

        var result = service.importFromCsv(csv(content));

        assertThat(result.errors()).isEmpty();
        assertThat(result.saved().get(0).getIsActive()).isFalse();
    }

    @Test
    void importFromCsv_linhaEmBranco_deveSerIgnoradaSemGerarErro() throws Exception {
        String content =
                "numero;bu;cliente;operacao;segmento;horario;intervalo;quantidade;ativo\n"
                        + "\n"
                        + "+5511999999999;Matriz;Cliente A;Op X;Varejo;08:00;30;5;true\n";

        var result = service.importFromCsv(csv(content));

        assertThat(result.errors()).isEmpty();
        assertThat(result.saved()).hasSize(1);
    }
}
