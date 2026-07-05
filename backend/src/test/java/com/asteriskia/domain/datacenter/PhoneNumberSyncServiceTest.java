package com.asteriskia.domain.datacenter;

import com.asteriskia.domain.connectivity.NumberTest;
import com.asteriskia.domain.connectivity.NumberTestRepository;
import com.asteriskia.domain.masterdata.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PhoneNumberSyncServiceTest — cobre o motor de sincronização DATACENTER → Conectividade.
 */
@ExtendWith(MockitoExtension.class)
class PhoneNumberSyncServiceTest {

    @Mock private PhoneNumberRepository phoneNumberRepo;
    @Mock private NumberTestRepository numberTestRepo;
    @Mock private BusinessUnitRepository businessUnitRepo;
    @Mock private ClientRepository clientRepo;
    @Mock private OperationRepository operationRepo;
    @Mock private SegmentRepository segmentRepo;

    private PhoneNumberSyncService service;

    private BusinessUnit bu;
    private Client client;
    private Operation operation;
    private Segment segment;

    @BeforeEach
    void setUp() {
        service = new PhoneNumberSyncService(phoneNumberRepo, numberTestRepo,
                businessUnitRepo, clientRepo, operationRepo, segmentRepo);

        bu = BusinessUnit.builder().id(1).name("BU Teste").build();
        client = Client.builder().id(2).name("Cliente Teste").isActive(true).build();
        operation = Operation.builder().id(3).name("Operação Teste").build();
        segment = Segment.builder().id(4).name("Segmento Teste").build();

        lenient().when(businessUnitRepo.findById(1)).thenReturn(Optional.of(bu));
        lenient().when(clientRepo.findById(2)).thenReturn(Optional.of(client));
        lenient().when(phoneNumberRepo.save(any(PhoneNumber.class))).thenAnswer(inv -> inv.getArgument(0));

        // Por padrão os testes rodam como ADMIN — a checagem de permissão
        // cruzada (requireWritePermission) é coberta em testes dedicados abaixo.
        authenticateAs("ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... authorities) {
        List<GrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-teste", null, granted));
    }

    @Test
    void createOrUpdate_ddrComOperacaoESegmento_criaNumberTestAtivo() {
        when(operationRepo.findById(3)).thenReturn(Optional.of(operation));
        when(segmentRepo.findById(4)).thenReturn(Optional.of(segment));
        when(numberTestRepo.findByPhoneNumberSourceId(any())).thenReturn(Optional.empty());
        when(numberTestRepo.save(any(NumberTest.class))).thenAnswer(inv -> inv.getArgument(0));

        PhoneNumberRequest req = new PhoneNumberRequest(
                "+5511999990000", NumberType.DDR, 1, 2, null, 3, 4, null, true);

        PhoneNumberSaveResult result = service.createOrUpdate(null, req);

        ArgumentCaptor<NumberTest> captor = ArgumentCaptor.forClass(NumberTest.class);
        verify(numberTestRepo).save(captor.capture());
        NumberTest saved = captor.getValue();

        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getPhoneNumber()).isEqualTo("+5511999990000");
        assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(saved.getIntervalMinutes()).isEqualTo(60);
        assertThat(saved.getQuantity()).isEqualTo(3);
        assertThat(result.usedSystemDefaultTemplate()).isTrue();
        assertThat(result.clientCreated()).isFalse();
    }

    @Test
    void createOrUpdate_segmentoComTemplatePropio_usaTemplateDoSegmento() {
        segment.setDefaultStartTime(LocalTime.of(9, 30));
        segment.setDefaultIntervalMinutes(120);
        segment.setDefaultQuantity(5);
        when(operationRepo.findById(3)).thenReturn(Optional.of(operation));
        when(segmentRepo.findById(4)).thenReturn(Optional.of(segment));
        when(numberTestRepo.findByPhoneNumberSourceId(any())).thenReturn(Optional.empty());
        when(numberTestRepo.save(any(NumberTest.class))).thenAnswer(inv -> inv.getArgument(0));

        PhoneNumberRequest req = new PhoneNumberRequest(
                "+5511999990000", NumberType.ZERO_OITO_ZERO_ZERO, 1, 2, null, 3, 4, null, true);

        PhoneNumberSaveResult result = service.createOrUpdate(null, req);

        ArgumentCaptor<NumberTest> captor = ArgumentCaptor.forClass(NumberTest.class);
        verify(numberTestRepo).save(captor.capture());
        NumberTest saved = captor.getValue();

        assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(saved.getIntervalMinutes()).isEqualTo(120);
        assertThat(saved.getQuantity()).isEqualTo(5);
        assertThat(result.usedSystemDefaultTemplate()).isFalse();
    }

    @Test
    void createOrUpdate_whatsapp_naoCriaNumberTest_mesmoComOperacaoESegmento() {
        when(operationRepo.findById(3)).thenReturn(Optional.of(operation));
        when(segmentRepo.findById(4)).thenReturn(Optional.of(segment));

        PhoneNumberRequest req = new PhoneNumberRequest(
                "5511999990000", NumberType.WHATSAPP, 1, 2, null, 3, 4, null, true);

        service.createOrUpdate(null, req);

        verify(numberTestRepo, never()).save(any());
    }

    @Test
    void createOrUpdate_semOperacaoOuSegmento_ficaPendenteSemCriarNumberTest() {
        PhoneNumberRequest req = new PhoneNumberRequest(
                "+5511999990000", NumberType.DDR, 1, 2, null, null, null, null, true);

        service.createOrUpdate(null, req);

        verify(numberTestRepo, never()).save(any());
        verify(operationRepo, never()).findById(any());
        verify(segmentRepo, never()).findById(any());
    }

    @Test
    void createOrUpdate_editandoParaRemoverOperacao_desativaNumberTestExistente() {
        PhoneNumber existingPn = PhoneNumber.builder().id(5L).build();
        when(phoneNumberRepo.findById(5L)).thenReturn(Optional.of(existingPn));

        NumberTest existing = NumberTest.builder().id(99L).isActive(true).build();
        when(numberTestRepo.findByPhoneNumberSourceId(any())).thenReturn(Optional.of(existing));
        when(numberTestRepo.save(any(NumberTest.class))).thenAnswer(inv -> inv.getArgument(0));

        // operationId e segmentId ausentes na edição → número volta a ficar pendente
        PhoneNumberRequest req = new PhoneNumberRequest(
                "+5511999990000", NumberType.DDR, 1, 2, null, null, null, null, true);

        service.createOrUpdate(5L, req);

        ArgumentCaptor<NumberTest> captor = ArgumentCaptor.forClass(NumberTest.class);
        verify(numberTestRepo).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
    }

    @Test
    void createOrUpdate_clienteNovoTexto_criaClienteAutomaticamente() {
        when(operationRepo.findById(3)).thenReturn(Optional.of(operation));
        when(segmentRepo.findById(4)).thenReturn(Optional.of(segment));
        when(clientRepo.findAll()).thenReturn(java.util.List.of());
        when(clientRepo.save(any(Client.class))).thenAnswer(inv -> {
            Client c = inv.getArgument(0);
            c.setId(50);
            return c;
        });
        when(numberTestRepo.findByPhoneNumberSourceId(any())).thenReturn(Optional.empty());
        when(numberTestRepo.save(any(NumberTest.class))).thenAnswer(inv -> inv.getArgument(0));

        PhoneNumberRequest req = new PhoneNumberRequest(
                "+5511999990000", NumberType.DDR, 1, null, "Cliente Novo Ltda", 3, 4, null, true);

        PhoneNumberSaveResult result = service.createOrUpdate(null, req);

        assertThat(result.clientCreated()).isTrue();
        assertThat(result.phoneNumber().getClient().getName()).isEqualTo("Cliente Novo Ltda");
        verify(clientRepo).save(argThat(c -> "Cliente Novo Ltda".equals(c.getName())));
    }

    @Test
    void createOrUpdate_semPermissaoMasterdata_bloqueiaCriacaoDeClienteNovo() {
        authenticateAs("PERM_WRITE_telecom.datacenter"); // sem PERM_WRITE_telecom.masterdata
        when(clientRepo.findAll()).thenReturn(List.of());

        PhoneNumberRequest req = new PhoneNumberRequest(
                "+5511999990000", NumberType.DDR, 1, null, "Cliente Novo Ltda", null, null, null, true);

        assertThatThrownBy(() -> service.createOrUpdate(null, req))
                .isInstanceOf(AccessDeniedException.class);
        verify(clientRepo, never()).save(any());
    }

    @Test
    void createOrUpdate_semPermissaoModulo2_bloqueiaCriacaoDeNumberTest() {
        authenticateAs("PERM_WRITE_telecom.datacenter"); // sem PERM_WRITE_telecom.modulo2
        when(operationRepo.findById(3)).thenReturn(Optional.of(operation));
        when(segmentRepo.findById(4)).thenReturn(Optional.of(segment));
        when(numberTestRepo.findByPhoneNumberSourceId(any())).thenReturn(Optional.empty());

        PhoneNumberRequest req = new PhoneNumberRequest(
                "+5511999990000", NumberType.DDR, 1, 2, null, 3, 4, null, true);

        assertThatThrownBy(() -> service.createOrUpdate(null, req))
                .isInstanceOf(AccessDeniedException.class);
        verify(numberTestRepo, never()).save(any());
    }

    @Test
    void beforeDelete_desativaNumberTestVinculado() {
        NumberTest existing = NumberTest.builder().id(77L).isActive(true).build();
        when(numberTestRepo.findByPhoneNumberSourceId(10L)).thenReturn(Optional.of(existing));
        when(numberTestRepo.save(any(NumberTest.class))).thenAnswer(inv -> inv.getArgument(0));

        PhoneNumber pn = PhoneNumber.builder().id(10L).build();
        service.beforeDelete(pn);

        ArgumentCaptor<NumberTest> captor = ArgumentCaptor.forClass(NumberTest.class);
        verify(numberTestRepo).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
    }
}
