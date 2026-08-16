package com.asteriskia.integration.ami;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AmiOriginateService — Origina chamadas no Asterisk via AMI (Asterisk Manager Interface).
 *
 * <p>Protocolo AMI: TCP texto, porta 5038 por padrão. Cada ação é um bloco de linhas "Chave:
 * Valor\r\n" terminado por "\r\n".
 *
 * <p>Ação Originate: - Channel: tecnologia/destino (ex: PJSIP/1001 ou PJSIP/+5511999999999@trunk) -
 * Context: contexto de destino no extensions.conf - Exten/Priority: onde cair após atender -
 * Variable: variáveis passadas para o dialplan (consumidas pelo Agente IA)
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
     * Origina uma chamada de alerta Zabbix via AMI. O canal cai no contexto 'asteriskia-alert' que
     * conecta ao Audiosocket.
     *
     * @param phoneNumber Número a discar (ex: +5511999999999)
     * @param callUuid UUID que será passado como variável e identificará a chamada no agente Python
     * @param severity Severidade do incidente
     * @param host Host afetado
     * @param incidentSummary Descrição do incidente
     * @return true se a ação foi enviada com sucesso ao AMI
     */
    public boolean originateAlertCall(
            String phoneNumber,
            String callUuid,
            String severity,
            String host,
            String incidentSummary) {

        // Sanitiza tudo que vem de fora antes de colocar em campos do protocolo AMI —
        // um CRLF nesses valores quebraria o bloco e poderia injetar ações extras.
        String safePhoneNumber = sanitizeAmiField(phoneNumber);
        String safeSeverity = sanitizeAmiField(severity);
        String safeHost = sanitizeAmiField(host);

        Map<String, String> action = new LinkedHashMap<>();
        action.put("Action", "Originate");
        action.put("ActionID", UUID.randomUUID().toString());
        action.put("Channel", "PJSIP/" + safePhoneNumber + "@tronco-sip");
        action.put("Context", "asteriskia-alert");
        action.put("Exten", "s");
        action.put("Priority", "1");
        action.put("CallerID", "VoipIA <0800>");
        action.put("Timeout", "30000");
        action.put("Async", "true");
        // Prefixo "alert-" no UUID permite que o agente Python identifique
        // o flow como ZABBIX_ALERT via _detect_flow_type()
        action.put(
                "Variable",
                "CALL_UUID=alert-"
                        + sanitizeAmiField(callUuid)
                        + ",ZABBIX_SEVERITY="
                        + safeSeverity
                        + ",ZABBIX_HOST="
                        + safeHost
                        + ",FLOW_TYPE=ZABBIX_ALERT");

        return sendAction(action);
    }

    /**
     * Origina uma chamada de teste de conectividade via AMI (Módulo 2). O canal cai no contexto
     * 'asteriskia-test' que apenas verifica a conectividade.
     *
     * @param phoneNumber Número a testar
     * @param testResultId ID do TestResult a atualizar com o resultado
     * @return true se a ação foi enviada com sucesso ao AMI
     */
    public boolean originateTestCall(String phoneNumber, Long testResultId) {
        String safePhoneNumber = sanitizeAmiField(phoneNumber);

        Map<String, String> action = new LinkedHashMap<>();
        action.put("Action", "Originate");
        action.put("ActionID", UUID.randomUUID().toString());
        action.put("Channel", "PJSIP/" + safePhoneNumber + "@tronco-sip");
        action.put("Context", "asteriskia-test");
        action.put("Exten", "s");
        action.put("Priority", "1");
        action.put("Timeout", "30000");
        action.put("Async", "true");
        action.put("Variable", "TEST_RESULT_ID=" + testResultId + ",FLOW_TYPE=CONNECTIVITY_TEST");

        return sendAction(action);
    }

    /**
     * Origina uma chamada de supervisão (Fase 6 do Call Center) para o ramal do supervisor,
     * executando {@code ChanSpy} sobre o ramal do agente monitorado ao atender. {@code ChanSpy}
     * faz correspondência por <b>prefixo</b> do nome do canal — passar só a extensão (ex:
     * {@code PJSIP/4001}) é suficiente, sem precisar do nome completo do canal ativo (que exigiria
     * rastrear o evento AMI exato de cada chamada, ainda não validado contra tráfego real).
     *
     * @param supervisorExtension ramal do supervisor (softphone WebRTC, ex: 9001) que vai receber
     *     a chamada de monitoria
     * @param targetExtension ramal do agente a ser monitorado (faixa 4000-4999)
     * @param chanSpyOptions opções do ChanSpy: {@code "b"} escuta, {@code "bw"} sussurro,
     *     {@code "bB"} interceptação (barge-in) — {@code b} restringe a canais em bridge, evitando
     *     escutar um ramal ocioso
     * @return true se a ação foi enviada com sucesso ao AMI
     */
    public boolean originateChanSpy(String supervisorExtension, String targetExtension, String chanSpyOptions) {
        String safeSupervisor = sanitizeAmiField(supervisorExtension);
        String safeTarget = sanitizeAmiField(targetExtension);
        String safeOptions = sanitizeAmiField(chanSpyOptions);

        Map<String, String> action = new LinkedHashMap<>();
        action.put("Action", "Originate");
        action.put("ActionID", UUID.randomUUID().toString());
        action.put("Channel", "PJSIP/" + safeSupervisor);
        action.put("Application", "ChanSpy");
        action.put("Data", "PJSIP/" + safeTarget + "," + safeOptions);
        action.put("CallerID", "Supervisao <0000>");
        action.put("Timeout", "30000");
        action.put("Async", "true");

        return sendAction(action);
    }

    /**
     * Retira um canal ativo de onde estiver (ex: em fila) e o redireciona para outro
     * contexto/extensão/prioridade do dialplan (Fase 15.3 — Call Center, ação de supervisão).
     *
     * @param channelName nome REAL do canal Asterisk (ex: {@code PJSIP/tronco-0000001a}) — a
     *     ação {@code Redirect} exige o nome, não o {@code Uniqueid}.
     * @param context contexto de destino no dialplan (ex: {@code ramais-internos})
     * @param exten extensão de destino (ramal de fila {@code _5XXX} ou de agente {@code _4XXX})
     * @param priority prioridade de destino no dialplan
     * @return true se a ação foi enviada com sucesso ao AMI
     */
    public boolean redirectChannel(String channelName, String context, String exten, int priority) {
        String safeChannel = sanitizeAmiField(channelName);
        String safeContext = sanitizeAmiField(context);
        String safeExten = sanitizeAmiField(exten);

        Map<String, String> action = new LinkedHashMap<>();
        action.put("Action", "Redirect");
        action.put("ActionID", UUID.randomUUID().toString());
        action.put("Channel", safeChannel);
        action.put("Context", safeContext);
        action.put("Exten", safeExten);
        action.put("Priority", String.valueOf(priority));

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
        try (AmiSession ami = AmiSession.connect(host, port, TIMEOUT_MS)) {
            // Autentica — não usa AmiSession.login() aqui porque o log de falha desta classe
            // sempre incluiu o corpo da resposta do AMI, diferente dos outros 3 chamadores.
            ami.send(
                    Map.of(
                            "Action", "Login",
                            "Username", user,
                            "Secret", password));
            String loginResponse = ami.readBlock();
            if (!loginResponse.contains("Success")) {
                log.error("AMI: Falha na autenticação. Resposta: {}", loginResponse);
                return false;
            }

            // Envia a ação
            ami.send(actionFields);
            String response = ami.readBlock();
            log.debug("AMI Originate resposta: {}", response);

            // Logoff
            ami.logoff();

            return response.contains("Success")
                    || response.contains("Originate successfully queued");

        } catch (SocketTimeoutException e) {
            log.error("AMI: Timeout de conexão com {}:{}", host, port);
        } catch (IOException e) {
            log.error("AMI: Erro de I/O: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Remove \r e \n de um valor antes de usá-lo em um campo AMI. O protocolo AMI é texto
     * delimitado por linhas — um valor vindo de fora (telefone, severidade, host, resumo do
     * incidente) com CRLF poderia quebrar o bloco da ação e injetar comandos extras na sessão
     * autenticada.
     */
    private String sanitizeAmiField(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "");
    }
}
