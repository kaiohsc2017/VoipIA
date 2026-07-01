package com.asteriskia.domain.call;

import com.asteriskia.integration.jira.JiraIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * CallRecordService — Lógica de negócio dos registros de chamada.
 *
 * Responsabilidades:
 *   1. Registrar chamada recebida pela URA
 *   2. Acionar integração com o Jira para criação do issue
 *   3. Salvar a chave do issue no registro
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallRecordService {

    private final CallRecordRepository repository;
    private final JiraIntegrationService jiraService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Registra uma nova chamada e cria o issue correspondente no Jira.
     *
     * @param callUuid UUID da chamada gerado pelo Asterisk
     * @param fields   Mapa de campos coletados pela URA (jira_field_key → valor)
     * @return Registro da chamada criado
     */
    @Transactional
    public CallRecord registerCall(
            String callUuid,
            Map<String, String> fields,
            String audioFilePath,
            String transcription,
            String callerNumber,
            Integer callDurationSecs) {

        log.info("Registrando chamada UUID={}", callUuid);

        // Número do chamador — usa o valor explícito se for real (não "desconhecido"),
        // caso contrário cai no ramal informado pelo usuário durante a URA.
        String callerPhone = callerNumber != null && !callerNumber.isBlank()
                    && !"desconhecido".equals(callerNumber)
                ? callerNumber
                : fields.getOrDefault("customfield_telefone", "desconhecido");

        // Nome do cliente (pode vir do STT das perguntas)
        String clientName = truncate(fields.getOrDefault("customfield_nome_cliente", null), 200);

        // Transcrição — prioriza o campo explícito consolidado
        String fullTranscription = transcription != null && !transcription.isBlank()
                ? transcription
                : fields.getOrDefault("description", "");

        UUID uuid;
        try {
            uuid = UUID.fromString(callUuid);
        } catch (IllegalArgumentException e) {
            log.warn("callUuid '{}' não é um UUID válido — gerando UUID aleatório", callUuid);
            uuid = UUID.randomUUID();
        }

        // Extrai tipo de atendimento das respostas da URA
        String callType = truncate(fields.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains("tipo")
                        || e.getKey().toLowerCase().contains("issuetype")
                        || e.getKey().toLowerCase().contains("type"))
                .map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null), 255);

        // Extrai impacto/prioridade das respostas da URA (chave configurável na tela de Fluxo URA)
        String priority = truncate(fields.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains("priority")
                        || e.getKey().toLowerCase().contains("prioridade")
                        || e.getKey().toLowerCase().contains("impacto"))
                .map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null), 255);

        // Ramal/telefone que o cliente informou por voz na URA (distinto do callerNumber real)
        String reportedRamal = truncate(fields.getOrDefault("customfield_telefone", null), 255);

        CallRecord record = CallRecord.builder()
                .callUuid(uuid)
                .callDate(LocalDateTime.now(java.time.ZoneId.systemDefault()))
                .callerNumber(callerPhone)
                .clientName(clientName)
                .transcription(fullTranscription)
                .audioFilePath(audioFilePath)
                .callType(callType)
                .reportedRamal(reportedRamal)
                .priority(priority)
                .callDurationSecs(callDurationSecs != null ? callDurationSecs : 0)
                .build();

        // Primeiro salva para garantir persistência mesmo que o Jira falhe
        record = repository.save(record);

        // Chama Jira Cloud para criar o issue
        try {
            String issueKey = jiraService.createIssue(fields);
            if (issueKey != null) {
                record.setJiraIssueKey(issueKey);
                record.setJiraIssueStatus("Aberto");
                record = repository.save(record);
                log.info("Chamada {} vinculada ao Jira issue {}", callUuid, issueKey);
            } else {
                log.warn("Chamada {} registrada sem issue Jira (falha na integração)", callUuid);
            }
        } catch (Exception e) {
            log.error("Erro na integração Jira para chamada {}: {}", callUuid, e.getMessage());
        }

        // Envia notificação WebSocket em tempo real para o Frontend
        try {
            messagingTemplate.convertAndSend("/topic/calls", record);
        } catch (Exception e) {
            log.warn("Erro ao enviar WebSocket de nova chamada: {}", e.getMessage());
        }

        return record;
    }

    @Transactional(readOnly = true)
    public Page<CallRecord> findAll(Pageable pageable) {
        return repository.findAllByOrderByCallDateDesc(pageable);
    }

    @Transactional(readOnly = true)
    public CallRecord findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CallRecord não encontrado: " + id));
    }

    @Transactional(readOnly = true)
    public Page<CallRecord> findByCallerNumber(String number, Pageable pageable) {
        return repository.findByCallerNumberContainingOrderByCallDateDesc(number, pageable);
    }

    @Transactional(readOnly = true)
    public Page<CallRecord> findByFilters(CallRecordFilter filter, Pageable pageable) {
        return repository.findAll(CallRecordSpecifications.withFilters(filter), pageable);
    }

    /**
     * Trunca texto extraído das respostas da URA para caber na coluna do banco.
     * O STT pode retornar frases longas de fallback (ex: "Não foi detectada
     * nenhuma prioridade no áudio.") em vez do valor curto esperado — sem isso,
     * o INSERT falha e a chamada inteira deixa de ser registrada.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
