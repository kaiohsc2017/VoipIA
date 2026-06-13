package com.asteriskia.integration.ami;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AmiOriginateService — Origina chamadas no Asterisk via AMI (Asterisk Manager Interface).
 *
 * Protocolo AMI: TCP texto, porta 5038 por padrão.
 * Cada ação é um bloco de linhas "Chave: Valor\r\n" terminado por "\r\n".
 *
 * Ação Originate:
 *   - Channel: tecnologia/destino (ex: PJSIP/1001 ou PJSIP/+5511999999999@trunk)
 *   - Context: contexto de destino no extensions.conf
 *   - Exten/Priority: onde cair após atender
 *   - Variable: variáveis passadas para o dialplan (consumidas pelo Agente IA)
 */
@Slf4j
@Service
public class AmiOriginateService {

    @Value("${app.asterisk.ami.host}")
    private String host;

    @Value("${app.asterisk.ami.port:5038}")
    private int port;

    @Value("${app.asterisk.ami.user}")
    private String user;

    @Value("${app.asterisk.ami.password}")
    private String password;

    private static final int TIMEOUT_MS = 10_000;

    /**
     * Origina uma chamada de alerta Zabbix via AMI.
     * O canal cai no contexto 'asteriskia-alert' que conecta ao Audiosocket.
     *
     * @param phoneNumber     Número a discar (ex: +5511999999999)
     * @param callUuid        UUID que será passado como variável e identificará a chamada no agente Python
     * @param severity        Severidade do incidente
     * @param host            Host afetado
     * @param incidentSummary Descrição do incidente
     * @return true se a ação foi enviada com sucesso ao AMI
     */
    public boolean originateAlertCall(
            String phoneNumber,
            String callUuid,
            String severity,
            String host,
            String incidentSummary) {

        Map<String, String> action = new LinkedHashMap<>();
        action.put("Action", "Originate");
        action.put("ActionID", UUID.randomUUID().toString());
        action.put("Channel", "PJSIP/" + phoneNumber + "@tronco-sip");
        action.put("Context", "asteriskia-alert");
        action.put("Exten", "s");
        action.put("Priority", "1");
        action.put("CallerID", "AsteriskIA <0800>");
        action.put("Timeout", "30000");
        action.put("Async", "true");
        // Prefixo "alert-" no UUID permite que o agente Python identifique
        // o flow como ZABBIX_ALERT via _detect_flow_type()
        action.put("Variable", "CALL_UUID=alert-" + callUuid
                + ",ZABBIX_SEVERITY=" + severity
                + ",ZABBIX_HOST=" + host
                + ",FLOW_TYPE=ZABBIX_ALERT");

        return sendAction(action);
    }

    /**
     * Origina uma chamada de teste de conectividade via AMI (Módulo 2).
     * O canal cai no contexto 'asteriskia-test' que apenas verifica a conectividade.
     *
     * @param phoneNumber  Número a testar
     * @param testResultId ID do TestResult a atualizar com o resultado
     * @return true se a ação foi enviada com sucesso ao AMI
     */
    public boolean originateTestCall(String phoneNumber, Long testResultId) {
        Map<String, String> action = new LinkedHashMap<>();
        action.put("Action", "Originate");
        action.put("ActionID", UUID.randomUUID().toString());
        action.put("Channel", "PJSIP/" + phoneNumber + "@tronco-sip");
        action.put("Context", "asteriskia-test");
        action.put("Exten", "s");
        action.put("Priority", "1");
        action.put("Timeout", "30000");
        action.put("Async", "true");
        action.put("Variable", "TEST_RESULT_ID=" + testResultId + ",FLOW_TYPE=CONNECTIVITY_TEST");

        return sendAction(action);
    }

    // ---------------------------------------------------------------------------
    // Privado — protocolo AMI TCP raw
    // ---------------------------------------------------------------------------

    /**
     * Abre uma conexão TCP com o AMI, autentica e envia uma ação.
     *
     * @param actionFields Campos da ação (ordem importa — usa LinkedHashMap)
     * @return true se a resposta contiver "Response: Success"
     */
    private boolean sendAction(Map<String, String> actionFields) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Lê banner de boas-vindas do AMI
            readLine(reader);

            // Autentica
            sendBlock(writer, Map.of(
                    "Action", "Login",
                    "Username", user,
                    "Secret", password
            ));
            String loginResponse = readUntilBlank(reader);
            if (!loginResponse.contains("Success")) {
                log.error("AMI: Falha na autenticação. Resposta: {}", loginResponse);
                return false;
            }

            // Envia a ação
            sendBlock(writer, actionFields);
            String response = readUntilBlank(reader);
            log.debug("AMI Originate resposta: {}", response);

            // Logoff
            sendBlock(writer, Map.of("Action", "Logoff"));

            return response.contains("Success") || response.contains("Originate successfully queued");

        } catch (SocketTimeoutException e) {
            log.error("AMI: Timeout de conexão com {}:{}", host, port);
        } catch (IOException e) {
            log.error("AMI: Erro de I/O: {}", e.getMessage());
        }
        return false;
    }

    /** Envia um bloco AMI (pares chave:valor + linha em branco final). */
    private void sendBlock(PrintWriter writer, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        writer.print(sb);
        writer.flush();
    }

    /** Lê linhas até encontrar linha em branco (fim de bloco AMI). */
    private String readUntilBlank(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = readLine(reader)) != null) {
            if (line.isEmpty()) break;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String readLine(BufferedReader reader) throws IOException {
        return reader.readLine();
    }
}
