package com.asteriskia.domain.call;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.ura.Ura;
import com.asteriskia.domain.ura.UraQuestion;
import com.asteriskia.domain.ura.UraQuestionRepository;
import com.asteriskia.domain.ura.UraRepository;
import com.asteriskia.integration.jira.JiraIntegrationService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallRecordService — Lógica de negócio dos registros de chamada.
 *
 * <p>Responsabilidades: 1. Registrar chamada recebida pela URA 2. Salvar as respostas por pergunta
 * (ura_answers) para o relatório 3. Acionar integração com o Jira para criação do issue, se a URA
 * tiver isso ativado
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallRecordService {

    /** URA legada (service desk) — usada quando o ai-agent ainda não informa uraId. */
    private static final int DEFAULT_URA_ID = 1;

    private final CallRecordRepository repository;
    private final UraRepository uraRepository;
    private final UraQuestionRepository uraQuestionRepository;
    private final UraAnswerRepository uraAnswerRepository;
    private final JiraIntegrationService jiraService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Registra uma nova chamada, salva as respostas por pergunta e (se a URA tiver a integração
     * ativada) cria o issue correspondente no Jira.
     *
     * @param callUuid UUID da chamada gerado pelo Asterisk
     * @param uraId Qual URA conduziu a chamada — null cai na URA legada (id=1)
     * @param fields Mapa de campos coletados pela URA (jira_field_key → valor)
     * @return Registro da chamada criado
     */
    @Transactional
    public CallRecord registerCall(
            String callUuid,
            Integer uraId,
            Map<String, String> fields,
            String audioFilePath,
            String transcription,
            String callerNumber,
            Integer callDurationSecs,
            String subjectTag,
            AiUsageInfo aiUsage) {

        int resolvedUraId = uraId != null ? uraId : DEFAULT_URA_ID;
        log.info("Registrando chamada UUID={} uraId={}", callUuid, resolvedUraId);

        // Uma chamada que falhou antes de coletar respostas (ex: cliente desligou
        // cedo) pode chegar sem nenhum field — nunca deixar isso abortar o registro.
        if (fields == null) fields = Map.of();
        if (aiUsage == null) aiUsage = AiUsageInfo.empty();

        CallRecord record =
                buildCallRecord(
                        callUuid,
                        resolvedUraId,
                        fields,
                        audioFilePath,
                        transcription,
                        callerNumber,
                        callDurationSecs,
                        subjectTag,
                        aiUsage);

        // Primeiro salva para garantir persistência mesmo que o Jira falhe
        record = repository.save(record);

        // Salva uma resposta por pergunta configurada — nunca exige alterar o schema
        saveAnswers(record.getId(), resolvedUraId, fields);

        record = applyJiraIntegration(record, resolvedUraId, fields);
        notifyNewCall(record);

        return record;
    }

    /** Monta o CallRecord a partir dos campos coletados pela URA — sem persistir. */
    private CallRecord buildCallRecord(
            String callUuid,
            int resolvedUraId,
            Map<String, String> fields,
            String audioFilePath,
            String transcription,
            String callerNumber,
            Integer callDurationSecs,
            String subjectTag,
            AiUsageInfo aiUsage) {

        // Número do chamador — usa o valor explícito se for real (não "desconhecido"),
        // caso contrário cai no ramal informado pelo usuário durante a URA.
        String callerPhone =
                callerNumber != null
                                && !callerNumber.isBlank()
                                && !"desconhecido".equals(callerNumber)
                        ? callerNumber
                        : fields.getOrDefault("customfield_telefone", "desconhecido");

        // Nome do cliente (pode vir do STT das perguntas)
        String clientName = truncate(fields.getOrDefault("customfield_nome_cliente", null), 200);

        // Transcrição — prioriza o campo explícito consolidado
        String fullTranscription =
                transcription != null && !transcription.isBlank()
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
        String callType =
                truncate(
                        firstFieldValueMatching(fields, "tipo", "issuetype", "type"), 255);

        // Extrai impacto/prioridade das respostas da URA (chave configurável na tela de Fluxo URA)
        String priority =
                truncate(
                        firstFieldValueMatching(fields, "priority", "prioridade", "impacto"), 255);

        // Ramal/telefone que o cliente informou por voz na URA (distinto do callerNumber real)
        String reportedRamal = truncate(fields.getOrDefault("customfield_telefone", null), 255);

        return CallRecord.builder()
                .uraId(resolvedUraId)
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
                .subjectTag(truncate(subjectTag, 100))
                .sttTokensIn(aiUsage.sttTokensInOrZero())
                .sttTokensOut(aiUsage.sttTokensOutOrZero())
                .sttModel(truncate(aiUsage.sttModel(), 100))
                .llmTokensIn(aiUsage.llmTokensInOrZero())
                .llmTokensOut(aiUsage.llmTokensOutOrZero())
                .llmModel(truncate(aiUsage.llmModel(), 100))
                .ttsTokensIn(aiUsage.ttsTokensInOrZero())
                .ttsTokensOut(aiUsage.ttsTokensOutOrZero())
                .ttsModel(truncate(aiUsage.ttsModel(), 100))
                .build();
    }

    /** Primeiro valor de `fields` cuja chave (case-insensitive) contém algum dos termos dados. */
    private static String firstFieldValueMatching(Map<String, String> fields, String... terms) {
        return fields.entrySet().stream()
                .filter(
                        e -> {
                            String key = e.getKey().toLowerCase();
                            for (String term : terms) {
                                if (key.contains(term)) return true;
                            }
                            return false;
                        })
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Cria o issue no Jira se a URA tiver a integração ativada, atualizando e salvando o record com
     * a chave/status resultantes. Falhas na integração são logadas, nunca propagadas — a chamada já
     * está persistida e não pode ser perdida por um problema no Jira.
     */
    private CallRecord applyJiraIntegration(
            CallRecord record, int resolvedUraId, Map<String, String> fields) {
        String callUuid = record.getCallUuid().toString();
        Ura ura = uraRepository.findById(resolvedUraId).orElse(null);
        boolean jiraEnabled = ura == null || Boolean.TRUE.equals(ura.getJiraIntegrationEnabled());
        if (!jiraEnabled) {
            log.info(
                    "Chamada {} — URA {} está com integração Jira desativada, pulando abertura de chamado",
                    callUuid,
                    resolvedUraId);
            return record;
        }

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
        return record;
    }

    /** Notifica o Frontend em tempo real via WebSocket — falha aqui não afeta o registro. */
    private void notifyNewCall(CallRecord record) {
        try {
            messagingTemplate.convertAndSend("/topic/calls", record);
        } catch (Exception e) {
            log.warn("Erro ao enviar WebSocket de nova chamada: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<CallRecord> findAll(Pageable pageable) {
        return attachAnswers(repository.findAll(businessUnitScope(), pageable));
    }

    @Transactional(readOnly = true)
    public CallRecord findById(Long id) {
        CallRecord record =
                repository
                        .findById(id)
                        .filter(this::inBusinessUnitScope)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "CallRecord não encontrado: " + id));
        record.setAnswers(
                loadAnswers(java.util.List.of(record.getId()))
                        .getOrDefault(record.getId(), java.util.List.of()));
        return record;
    }

    /**
     * Atualiza o assunto classificado por IA (subject_tag) de um chamado já registrado — usado pelo
     * backfill em lote do ai-agent para classificar chamadas antigas que foram registradas antes da
     * funcionalidade existir (nunca passaram pelo fluxo em tempo real de {@link #registerCall}).
     */
    @Transactional
    public void updateSubjectTag(Long id, String subjectTag) {
        CallRecord record =
                repository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "CallRecord não encontrado: " + id));
        record.setSubjectTag(truncate(subjectTag, 100));
        repository.save(record);
    }

    @Transactional(readOnly = true)
    public Page<CallRecord> findByCallerNumber(String number, Pageable pageable) {
        Specification<CallRecord> spec =
                (root, query, cb) -> cb.like(root.get("callerNumber"), "%" + number + "%");
        return attachAnswers(repository.findAll(spec.and(businessUnitScope()), pageable));
    }

    @Transactional(readOnly = true)
    public Page<CallRecord> findByFilters(CallRecordFilter filter, Pageable pageable) {
        return attachAnswers(
                repository.findAll(
                        CallRecordSpecifications.withFilters(filter).and(businessUnitScope()),
                        pageable));
    }

    /** Controle de acesso por BU — Specification neutra (sem restrição) para ADMIN. */
    private Specification<CallRecord> businessUnitScope() {
        if (!BusinessUnitContext.isRestricted()) {
            return Specification.where(null);
        }
        return CallRecordSpecifications.restrictedToBusinessUnits(
                BusinessUnitContext.currentBusinessUnitIds());
    }

    /** true se a chamada é visível ao usuário atual — ADMIN sempre vê tudo. */
    private boolean inBusinessUnitScope(CallRecord record) {
        if (!BusinessUnitContext.isRestricted()) {
            return true;
        }
        Ura ura = uraRepository.findById(record.getUraId()).orElse(null);
        if (ura == null || ura.getBusinessUnit() == null) {
            return true;
        }
        return BusinessUnitContext.currentBusinessUnitIds().contains(ura.getBusinessUnit().getId());
    }

    /**
     * Preenche o campo transiente `answers` de cada registro da página (uma consulta em lote, não
     * N+1).
     */
    private Page<CallRecord> attachAnswers(Page<CallRecord> page) {
        java.util.List<Long> ids = page.getContent().stream().map(CallRecord::getId).toList();
        java.util.Map<Long, java.util.List<CallRecord.AnswerView>> byCall = loadAnswers(ids);
        page.getContent()
                .forEach(r -> r.setAnswers(byCall.getOrDefault(r.getId(), java.util.List.of())));
        return page;
    }

    private java.util.Map<Long, java.util.List<CallRecord.AnswerView>> loadAnswers(
            java.util.List<Long> callRecordIds) {
        if (callRecordIds.isEmpty()) return java.util.Map.of();

        java.util.List<UraAnswer> allAnswers =
                uraAnswerRepository.findByCallRecordIdIn(callRecordIds);
        if (allAnswers.isEmpty()) return java.util.Map.of();

        java.util.Map<Integer, UraQuestion> questionsById =
                uraQuestionRepository
                        .findAllById(
                                allAnswers.stream()
                                        .map(UraAnswer::getUraQuestionId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(UraQuestion::getId, q -> q));

        return allAnswers.stream()
                .collect(
                        java.util.stream.Collectors.groupingBy(
                                UraAnswer::getCallRecordId,
                                java.util.stream.Collectors.mapping(
                                        a -> {
                                            UraQuestion q = questionsById.get(a.getUraQuestionId());
                                            return new CallRecord.AnswerView(
                                                    a.getUraQuestionId(),
                                                    q != null ? q.getQuestionText() : "?",
                                                    a.getValue());
                                        },
                                        java.util.stream.Collectors.toList())));
    }

    /**
     * Salva uma linha em ura_answers para cada campo que corresponde a uma pergunta configurada da
     * URA. Campos que não batem com nenhuma pergunta (ex: customfield_telefone injetado como
     * fallback do callerNumber) são ignorados aqui — só vão para o Jira via o mapa `fields`.
     *
     * <p>Carrega todas as perguntas da URA numa única consulta (em vez de uma consulta + um save
     * por campo) e persiste tudo de uma vez com saveAll.
     */
    private void saveAnswers(Long callRecordId, Integer uraId, Map<String, String> fields) {
        Map<String, UraQuestion> questionsByFieldKey =
                uraQuestionRepository.findByUraIdOrderByQuestionOrderAsc(uraId).stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        UraQuestion::getJiraFieldKey, q -> q, (a, b) -> a));

        java.util.List<UraAnswer> answers =
                fields.entrySet().stream()
                        .map(
                                entry -> {
                                    UraQuestion question = questionsByFieldKey.get(entry.getKey());
                                    if (question == null) return null;
                                    return UraAnswer.builder()
                                            .callRecordId(callRecordId)
                                            .uraQuestionId(question.getId())
                                            .value(entry.getValue())
                                            .build();
                                })
                        .filter(java.util.Objects::nonNull)
                        .toList();

        if (!answers.isEmpty()) {
            uraAnswerRepository.saveAll(answers);
        }
    }

    /**
     * Trunca texto extraído das respostas da URA para caber na coluna do banco. O STT pode retornar
     * frases longas de fallback (ex: "Não foi detectada nenhuma prioridade no áudio.") em vez do
     * valor curto esperado — sem isso, o INSERT falha e a chamada inteira deixa de ser registrada.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
