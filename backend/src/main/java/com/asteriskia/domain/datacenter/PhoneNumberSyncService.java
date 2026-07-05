package com.asteriskia.domain.datacenter;

import com.asteriskia.domain.connectivity.NumberTest;
import com.asteriskia.domain.connectivity.NumberTestRepository;
import com.asteriskia.domain.masterdata.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

/**
 * PhoneNumberSyncService — motor de sincronização DATACENTER → Conectividade.
 *
 * Equivalente ao {@code MonitoringService.syncToMonitoring} do ManagerTelecom,
 * adaptado à postura de integridade de dados do AsteriskIA: em vez de apagar o
 * teste de conectividade quando o número é removido ou perde Operação/Segmento,
 * ele é apenas desativado — preserva o histórico de TestResult.
 *
 * Regras:
 *   - WhatsApp nunca gera NumberTest (não é uma chamada SIP).
 *   - DDR/0800 só geram NumberTest quando Operação E Segmento estão definidos;
 *     enquanto algum dos dois faltar, o número fica "pendente".
 *   - O NumberTest novo nasce com o template padrão do Segmento (se
 *     configurado) ou um default de sistema (08:00, 60min, 3×).
 *
 * Achado de segurança (revisão pré-commit): PERM_WRITE_telecom.datacenter por
 * si só permite criar Cliente (telecom.masterdata) e mutar NumberTest
 * (telecom.modulo2) como efeito colateral — isso furava o RBAC granular caso
 * um grupo customizado tenha write em datacenter mas não nos outros dois
 * recursos. requireWritePermission() fecha essa escalada, exigindo a
 * permissão do recurso de destino antes de cada efeito colateral cruzado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneNumberSyncService {

    private static final LocalTime DEFAULT_START_TIME = LocalTime.of(8, 0);
    private static final int DEFAULT_INTERVAL_MINUTES = 60;
    private static final int DEFAULT_QUANTITY = 3;

    private final PhoneNumberRepository phoneNumberRepo;
    private final NumberTestRepository numberTestRepo;
    private final BusinessUnitRepository businessUnitRepo;
    private final ClientRepository clientRepo;
    private final OperationRepository operationRepo;
    private final SegmentRepository segmentRepo;

    @Transactional
    public PhoneNumberSaveResult createOrUpdate(Long existingId, PhoneNumberRequest req) {
        BusinessUnit businessUnit = businessUnitRepo.findById(req.businessUnitId())
                .orElseThrow(() -> new RuntimeException("BusinessUnit não encontrada: " + req.businessUnitId()));

        boolean[] clientCreated = {false};
        Client client = resolveClient(req, clientCreated);

        Operation operation = req.operationId() != null
                ? operationRepo.findById(req.operationId())
                        .orElseThrow(() -> new RuntimeException("Operação não encontrada: " + req.operationId()))
                : null;

        Segment segment = req.segmentId() != null
                ? segmentRepo.findById(req.segmentId())
                        .orElseThrow(() -> new RuntimeException("Segmento não encontrado: " + req.segmentId()))
                : null;

        PhoneNumber pn = existingId != null
                ? phoneNumberRepo.findById(existingId)
                        .orElseThrow(() -> new RuntimeException("Número não encontrado: " + existingId))
                : new PhoneNumber();

        pn.setPhoneNumber(req.phoneNumber());
        pn.setNumberType(req.numberType());
        pn.setBusinessUnit(businessUnit);
        pn.setClient(client);
        pn.setOperation(operation);
        pn.setSegment(segment);
        pn.setObservation(req.observation());
        pn.setIsActive(req.isActive() == null || req.isActive());

        PhoneNumber saved = phoneNumberRepo.save(pn);
        boolean usedDefault = syncNumberTest(saved);

        return new PhoneNumberSaveResult(saved, clientCreated[0], usedDefault);
    }

    /** Desativa (sem apagar) o NumberTest vinculado antes da exclusão do PhoneNumber. */
    @Transactional
    public void beforeDelete(PhoneNumber pn) {
        deactivateLinkedTest(pn.getId());
    }

    private Client resolveClient(PhoneNumberRequest req, boolean[] clientCreatedFlag) {
        if (req.clientId() != null) {
            return clientRepo.findById(req.clientId())
                    .orElseThrow(() -> new RuntimeException("Cliente não encontrado: " + req.clientId()));
        }
        if (req.newClientName() == null || req.newClientName().isBlank()) {
            throw new IllegalArgumentException("Informe clientId ou newClientName.");
        }
        String normalizedTarget = NameNormalizer.normalize(req.newClientName());
        return clientRepo.findAll().stream()
                .filter(c -> NameNormalizer.normalize(c.getName()).equals(normalizedTarget))
                .findFirst()
                .orElseGet(() -> {
                    requireWritePermission("telecom.masterdata");
                    clientCreatedFlag[0] = true;
                    Client novo = Client.builder()
                            .name(req.newClientName().trim())
                            .isActive(true)
                            .build();
                    return clientRepo.save(novo);
                });
    }

    /** @return true se o teste criado usou o template padrão de sistema (segmento sem template próprio). */
    private boolean syncNumberTest(PhoneNumber pn) {
        boolean pendente = pn.getOperation() == null || pn.getSegment() == null;
        boolean elegivel = pn.getNumberType() != NumberType.WHATSAPP && Boolean.TRUE.equals(pn.getIsActive());

        if (pendente || !elegivel) {
            deactivateLinkedTest(pn.getId());
            return false;
        }

        NumberTest existing = numberTestRepo.findByPhoneNumberSourceId(pn.getId()).orElse(null);
        Segment segment = pn.getSegment();

        LocalTime startTime = segment.getDefaultStartTime() != null ? segment.getDefaultStartTime() : DEFAULT_START_TIME;
        Integer interval = segment.getDefaultIntervalMinutes() != null ? segment.getDefaultIntervalMinutes() : DEFAULT_INTERVAL_MINUTES;
        Integer quantity = segment.getDefaultQuantity() != null ? segment.getDefaultQuantity() : DEFAULT_QUANTITY;
        boolean usedDefault = segment.getDefaultStartTime() == null
                && segment.getDefaultIntervalMinutes() == null
                && segment.getDefaultQuantity() == null;

        NumberTest test = existing != null ? existing : new NumberTest();
        test.setPhoneNumber(pn.getPhoneNumber());
        test.setPhoneNumberSource(pn);
        test.setBusinessUnit(pn.getBusinessUnit());
        test.setClient(pn.getClient());
        test.setOperation(pn.getOperation());
        test.setSegment(segment);
        test.setIsActive(true);
        if (existing == null) {
            // Só aplica o template ao criar — edições subsequentes do PhoneNumber
            // não devem sobrescrever um agendamento já ajustado manualmente.
            test.setStartTime(startTime);
            test.setIntervalMinutes(interval);
            test.setQuantity(quantity);
        }

        requireWritePermission("telecom.modulo2");
        numberTestRepo.save(test);
        return existing == null && usedDefault;
    }

    private void deactivateLinkedTest(Long phoneNumberId) {
        if (phoneNumberId == null) return;
        numberTestRepo.findByPhoneNumberSourceId(phoneNumberId).ifPresent(t -> {
            requireWritePermission("telecom.modulo2");
            t.setIsActive(false);
            numberTestRepo.save(t);
        });
    }

    /**
     * Impede que PERM_WRITE_telecom.datacenter, por si só, permita mutar
     * recursos de outro resource_key (Cliente/NumberTest) como efeito
     * colateral — ROLE_ADMIN e ROLE_INTERNAL continuam liberados, igual ao
     * resto do SecurityConfig.
     */
    private void requireWritePermission(String resourceKey) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = auth != null && auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_INTERNAL".equals(a.getAuthority())
                        || ("PERM_WRITE_" + resourceKey).equals(a.getAuthority()));
        if (!allowed) {
            throw new AccessDeniedException("Permissão insuficiente para escrita em " + resourceKey);
        }
    }
}
