package com.asteriskia.domain.callcenter.recording;

import com.asteriskia.integration.ami.AmiSession;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * CallCenterRecordingControlService — pausa/retoma a gravação MixMonitor de um canal ativo via
 * AMI (Action: MixMonitorMute), para o caso de dado sensível (ex: número de cartão) sendo ditado
 * durante o atendimento.
 *
 * <p><b>Por que não tem controller/endpoint público ainda:</b> pausar a gravação exige saber qual
 * é o canal ativo da chamada em andamento, e não existe hoje nenhuma tela que exponha isso — o
 * Desktop do Agente (Fase 4) ou um nó de "pausar gravação" no Flow Builder (Fase 5) é quem vai
 * chamar {@link #pause}/{@link #resume} com o {@code channelUniqueId} da chamada corrente. Esta
 * classe deixa a capacidade pronta no backend para esse consumidor futuro, sem UI nesta entrega
 * (escopo confirmado com o usuário em 2026-08-06).
 */
@Slf4j
@Service
public class CallCenterRecordingControlService {

    private static final int TIMEOUT_MS = 5_000;

    @Value("${app.asterisk.ami.host}")
    private String host;

    @Value("${app.asterisk.ami.port:5038}")
    private int port;

    @Value("${app.asterisk.ami.user}")
    private String user;

    @Value("${app.asterisk.ami.password}")
    private String password;

    public boolean pause(String channelUniqueId) {
        return sendMixMonitorMute(channelUniqueId, true);
    }

    public boolean resume(String channelUniqueId) {
        return sendMixMonitorMute(channelUniqueId, false);
    }

    private boolean sendMixMonitorMute(String channelUniqueId, boolean mute) {
        String safeChannel = sanitizeAmiField(channelUniqueId);
        try (AmiSession ami = AmiSession.connect(host, port, TIMEOUT_MS)) {
            if (!ami.login(user, password)) {
                log.error("AMI: falha na autenticação ao tentar {} gravação do canal {}",
                        mute ? "pausar" : "retomar", safeChannel);
                return false;
            }
            ami.send(
                    Map.of(
                            "Action", "MixMonitorMute",
                            "Channel", safeChannel,
                            "State", mute ? "1" : "0"));
            String response = ami.readBlock();
            ami.logoff();
            return response.contains("Success");
        } catch (SocketTimeoutException e) {
            log.error("AMI: timeout ao {} gravação do canal {}", mute ? "pausar" : "retomar", safeChannel);
        } catch (IOException e) {
            log.error("AMI: erro de I/O ao {} gravação: {}", mute ? "pausar" : "retomar", e.getMessage());
        }
        return false;
    }

    private String sanitizeAmiField(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "");
    }
}
