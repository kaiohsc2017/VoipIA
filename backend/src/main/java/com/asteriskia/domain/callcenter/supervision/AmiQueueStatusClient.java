package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.integration.ami.AmiSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AmiQueueStatusClient — consulta {@code Action: QueueStatus} ao vivo para o painel de
 * supervisão (Fase 15.1), diferente de {@link com.asteriskia.integration.ami.AmiOriginateService}
 * (request/response de bloco único) e de {@link CallCenterAmiEventListener} (conexão persistente
 * event-driven).
 *
 * <p><b>Decisão de design</b>: em vez de multiplexar esta consulta na conexão persistente do
 * listener — o que exigiria rotear eventos entre a thread de fundo e a thread da requisição HTTP,
 * com risco real de corromper o socket único do listener em produção — cada chamada abre sua
 * própria conexão AMI curta, dedicada, com {@code Events: on} (para receber os eventos
 * {@code QueueParams}/{@code QueueMember}/{@code QueueEntry}/{@code QueueStatusComplete} gerados
 * pela ação) e {@code ActionID} próprio, lê até {@code QueueStatusComplete} e fecha. Mais simples,
 * sem concorrência compartilhada, ao custo de uma conexão TCP extra por consulta (aceitável no
 * volume desta VPS de dev — revisitável se o volume real justificar reuso de conexão).
 */
@Slf4j
@Service
public class AmiQueueStatusClient {

    @Value("${app.asterisk.ami.host}")
    private String host;

    @Value("${app.asterisk.ami.port:5038}")
    private int port;

    @Value("${app.asterisk.ami.user}")
    private String user;

    @Value("${app.asterisk.ami.password}")
    private String password;

    private static final int TIMEOUT_MS = 5000;

    /** @return chamadores em espera na fila, ordenados pela {@code Position} reportada pelo
     *     Asterisk; lista vazia (nunca exceção) se o AMI estiver indisponível ou a fila não
     *     existir — o painel de supervisão não pode quebrar por causa disso. */
    public List<WaitingCallerView> queueStatus(String queueName) {
        if (queueName == null || queueName.isBlank()) {
            return List.of();
        }
        try (AmiSession ami = AmiSession.connect(host, port, TIMEOUT_MS)) {
            ami.send(
                    Map.of(
                            "Action", "Login",
                            "Username", user,
                            "Secret", password,
                            "Events", "on"));
            if (!ami.readBlock().contains("Success")) {
                log.warn("AmiQueueStatusClient: falha na autenticação AMI com {}:{}.", host, port);
                return List.of();
            }

            Map<String, String> action = new LinkedHashMap<>();
            action.put("Action", "QueueStatus");
            action.put("ActionID", UUID.randomUUID().toString());
            action.put("Queue", sanitizeAmiField(queueName));
            ami.send(action);

            String raw = ami.readUntil(line -> line.startsWith("Event: QueueStatusComplete"));
            ami.logoff();
            return parseEntries(raw);
        } catch (IOException e) {
            log.warn("AmiQueueStatusClient: erro ao consultar fila {}: {}", queueName, e.getMessage());
            return List.of();
        }
    }

    List<WaitingCallerView> parseEntries(String raw) {
        List<WaitingCallerView> entries = new ArrayList<>();
        for (String block : raw.split("\n\n")) {
            Map<String, String> fields = parseBlock(block);
            if (!"QueueEntry".equals(fields.get("Event"))) {
                continue;
            }
            entries.add(
                    new WaitingCallerView(
                            parseIntOrNull(fields.get("Position")),
                            fields.get("CallerIDNum"),
                            parseLongOrNull(fields.get("Wait")),
                            fields.get("Uniqueid"),
                            fields.get("Channel")));
        }
        return entries;
    }

    private Map<String, String> parseBlock(String rawBlock) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : rawBlock.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int sep = line.indexOf(':');
            if (sep < 0) {
                continue;
            }
            fields.put(line.substring(0, sep).trim(), line.substring(sep + 1).trim());
        }
        return fields;
    }

    private Integer parseIntOrNull(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sanitizeAmiField(String value) {
        return value.replace("\r", "").replace("\n", "");
    }
}
