package com.asteriskia.domain.alert;

import com.asteriskia.integration.ami.AmiOriginateService;
import com.asteriskia.telegram.TelegramBotService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AlertService — Orquestra ligações de alerta para incidentes Zabbix (Módulo 3).
 *
 * <p>Fluxo: 1. ZabbixPollingService detecta incidente 2. AlertService.triggerAlert() cria AlertCall
 * 3. AMI origina chamada para o contato de plantão 4. Telegram recebe notificação com status 5.
 * ZabbixAlertFlow (agente Python) atualiza status via PATCH /api/v1/alert-calls/by-uuid/{uuid}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertCallRepository alertCallRepo;
    private final AlertContactRepository contactRepo;
    private final AmiOriginateService amiService;
    private final TelegramBotService telegramService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Dispara ligação de alerta para os contatos ativos de plantão.
     *
     * @param triggerId ID do trigger no Zabbix (evita duplicidade)
     * @param incidentSummary Descrição do incidente
     * @param severity Severidade (High, Disaster, etc.)
     * @param host Host afetado
     */
    @Transactional
    public void triggerAlert(
            String triggerId, String incidentSummary, String severity, String host) {
        // Verifica duplicidade: não dispara se já existe alerta ativo para este trigger
        boolean alreadyActive =
                alertCallRepo.existsByZabbixTriggerIdAndCallStatusIn(
                        triggerId, List.of("PENDENTE", "ATENDIDA"));
        if (alreadyActive) {
            log.info("Alerta para trigger {} já está ativo. Ignorando.", triggerId);
            return;
        }

        // Busca contatos de plantão ativos, ordenados por prioridade
        List<AlertContact> contacts = contactRepo.findByIsActiveTrueOrderByPriorityOrderAsc();
        if (contacts.isEmpty()) {
            log.warn("Nenhum contato de plantão ativo. Alerta {} não será discado.", triggerId);
            // Mesmo sem contato, envia Telegram
            telegramService.sendMessage(
                    String.format(
                            "🚨 *ALERTA SEM CONTATO CONFIGURADO*\n\n*Trigger:* `%s`\n*Host:* `%s`\n*Incidente:* %s",
                            triggerId, host, incidentSummary));
            return;
        }

        // Para cada contato: cria registro + origina chamada AMI
        for (AlertContact contact : contacts) {
            String callUuid = UUID.randomUUID().toString();

            AlertCall alertCall =
                    AlertCall.builder()
                            .callDate(LocalDateTime.now())
                            .phoneNumber(contact.getPhoneNumber())
                            .callStatus("PENDENTE")
                            .zabbixTriggerId(triggerId)
                            .zabbixIncidentSummary(incidentSummary)
                            .zabbixSeverity(severity)
                            .zabbixHost(host)
                            .asteriskCallId(callUuid)
                            .build();

            alertCallRepo.save(alertCall);

            // Publica novo alerta via WebSocket para o Dashboard
            try {
                messagingTemplate.convertAndSend("/topic/alerts", alertCall);
            } catch (Exception e) {
                log.warn("Erro ao enviar WebSocket de AlertCall: {}", e.getMessage());
            }

            // Origina a chamada no Asterisk
            boolean originated =
                    amiService.originateAlertCall(
                            contact.getPhoneNumber(), callUuid, severity, host, incidentSummary);

            if (originated) {
                log.info(
                        "Chamada de alerta originada para {} (uuid={})",
                        contact.getPhoneNumber(),
                        callUuid);
                break; // Agenda apenas o primeiro contato disponível
            } else {
                log.warn(
                        "Falha ao originar chamada para {}. Tentando próximo contato.",
                        contact.getPhoneNumber());
                alertCall.setCallStatus("FALHA");
                alertCallRepo.save(alertCall);
            }
        }
    }

    /** Atualiza o status da chamada após o agente Python terminar o fluxo. */
    @Transactional
    public void updateCallStatus(String callUuid, String newStatus) {
        alertCallRepo
                .findByAsteriskCallId(callUuid)
                .ifPresent(
                        call -> {
                            call.setCallStatus(newStatus);
                            alertCallRepo.save(call);
                            log.info(
                                    "AlertCall uuid={} status atualizado para {}",
                                    callUuid,
                                    newStatus);

                            // Publica atualização de status via WebSocket
                            try {
                                messagingTemplate.convertAndSend("/topic/alerts", call);
                            } catch (Exception e) {
                                log.warn(
                                        "Erro ao enviar WebSocket de AlertCall: {}",
                                        e.getMessage());
                            }

                            // Notifica Telegram com resultado final
                            String msg =
                                    telegramService.sendZabbixAlert(
                                            call.getZabbixSeverity(),
                                            call.getZabbixHost(),
                                            call.getZabbixIncidentSummary(),
                                            call.getPhoneNumber(),
                                            newStatus);
                            call.setTelegramMessageContent(msg);
                            call.setTelegramSentAt(LocalDateTime.now());
                            alertCallRepo.save(call);
                        });
    }

    /** Busca AlertCall pelo asteriskCallId (usado pelo agente Python via /by-uuid/{uuid}). */
    @Transactional(readOnly = true)
    public Optional<AlertCall> findByUuid(String uuid) {
        return alertCallRepo.findByAsteriskCallId(uuid);
    }

    /** Busca AlertCall por ID primário (endpoints de áudio/detalhe). */
    @Transactional(readOnly = true)
    public Optional<AlertCall> findById(Long id) {
        return alertCallRepo.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<AlertCall> findAll(Pageable pageable) {
        return alertCallRepo.findAllByOrderByCallDateDesc(pageable);
    }

    @Transactional(readOnly = true)
    public List<AlertContact> findActiveContacts(Long operationId) {
        if (operationId != null) {
            return contactRepo.findByIsActiveTrueAndOperationIdOrderByPriorityOrderAsc(operationId);
        }
        return contactRepo.findByIsActiveTrueOrderByPriorityOrderAsc();
    }

    @Transactional
    public AlertContact saveContact(AlertContact contact) {
        return contactRepo.save(contact);
    }

    @Transactional
    public void deleteContact(Integer id) {
        contactRepo.deleteById(id);
    }
}
