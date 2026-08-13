package com.asteriskia.integration.ami;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AmiOriginateServiceRedirectTest — cobre {@code redirectChannel} (Fase 15.3) contra um servidor
 * AMI falso em socket local, a única forma de exercitar o protocolo real desta classe sem um
 * Asterisk de verdade (mesma limitação de {@code AmiOriginateService} como um todo — nunca teve
 * suíte própria antes desta fase).
 */
class AmiOriginateServiceRedirectTest {

    private ServerSocket serverSocket;
    private AmiOriginateService service;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        service = new AmiOriginateService();
        ReflectionTestUtils.setField(service, "host", "127.0.0.1");
        ReflectionTestUtils.setField(service, "port", serverSocket.getLocalPort());
        ReflectionTestUtils.setField(service, "user", "ami-user");
        ReflectionTestUtils.setField(service, "password", "ami-pass");
    }

    @AfterEach
    void tearDown() throws IOException {
        serverSocket.close();
    }

    @Test
    void redirectChannel_sanitizesCrlfInjection_andSendsExpectedFields() throws Exception {
        var received = new StringBuilder();
        CompletableFuture<Void> serverTask =
                CompletableFuture.runAsync(
                        () -> {
                            try (Socket client = serverSocket.accept();
                                    OutputStream out = client.getOutputStream();
                                    BufferedReader in =
                                            new BufferedReader(
                                                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                                out.write("Asterisk Call Manager/9.0.0\r\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                readBlock(in); // Login
                                out.write("Response: Success\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                String action = readBlock(in); // Redirect
                                received.append(action);
                                out.write(
                                        "Response: Success\r\nMessage: Redirect successful\r\n\r\n"
                                                .getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                readBlock(in); // Logoff
                            } catch (IOException ignored) {
                                // encerramento do teste — nada útil a fazer aqui.
                            }
                        });

        boolean ok =
                service.redirectChannel(
                        "PJSIP/tronco-1\r\nAction: Hangup", "ramais-internos\r\nEvil: 1", "5001", 1);
        serverTask.get();

        assertThat(ok).isTrue();
        assertThat(received.toString())
                .contains("Action: Redirect")
                .contains("Channel: PJSIP/tronco-1Action: Hangup")
                .contains("Context: ramais-internosEvil: 1")
                .contains("Exten: 5001")
                .contains("Priority: 1")
                .doesNotContain("\r\n\r\nAction: Hangup")
                .doesNotContain("Evil: 1\r\n\r\n");
    }

    private String readBlock(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
