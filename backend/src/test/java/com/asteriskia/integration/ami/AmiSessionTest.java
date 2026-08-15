package com.asteriskia.integration.ami;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * AmiSessionTest — cobre o achado real de 2026-08-15 (primeira validação de
 * {@code CallCenterAmiEventListener} com tráfego real de fila): uma conexão AMI aberta com
 * SO_TIMEOUT=0 (bloqueio infinito) trava para sempre num {@code read()} quando o Asterisk é
 * reiniciado e o socket fica preso sem FIN/RST perceptível — o listener nunca reconecta sozinho,
 * silenciosamente, sem log de erro. A correção usa um timeout finito (ver
 * {@code CallCenterAmiEventListener.AMI_READ_TIMEOUT_MS}), o que faz {@link AmiSession#readBlock()}
 * lançar {@link SocketTimeoutException} (subtipo de {@link IOException}) quando o peer fica em
 * silêncio além do prazo — o chamador então cai no mesmo caminho de reconexão de qualquer outra
 * falha de I/O.
 */
class AmiSessionTest {

    @Test
    void readBlock_peerGoesSilent_throwsSocketTimeoutExceptionWithinConfiguredWindow()
            throws Exception {
        try (var server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            // Servidor AMI falso: manda o banner, aceita o Login, responde Success, e depois
            // fica em silêncio para sempre — reproduz exatamente o socket "preso" do achado real.
            CompletableFuture.runAsync(
                    () -> {
                        try (Socket client = server.accept();
                                var writer =
                                        new PrintWriter(
                                                new java.io.OutputStreamWriter(
                                                        client.getOutputStream(),
                                                        StandardCharsets.UTF_8),
                                                true)) {
                            writer.print("Asterisk Call Manager/8.0.0\r\n");
                            writer.flush();
                            // Consome a ação de Login (não valida o conteúdo, só drena a linha
                            // em branco final) antes de responder.
                            var reader =
                                    new java.io.BufferedReader(
                                            new java.io.InputStreamReader(
                                                    client.getInputStream(),
                                                    StandardCharsets.UTF_8));
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                // drena
                            }
                            writer.print("Response: Success\r\n\r\n");
                            writer.flush();
                            // Silêncio proposital daqui em diante — nunca mais escreve nem fecha.
                            Thread.sleep(5_000);
                        } catch (IOException | InterruptedException ignored) {
                            // Encerramento do teste — nada a fazer.
                        }
                    });

            var ami = AmiSession.connect("localhost", port, 200);
            boolean loggedIn = ami.login("user", "secret");
            assertThat(loggedIn).isTrue();

            // A partir daqui o servidor fica em silêncio — com SO_TIMEOUT finito, o próximo
            // readBlock() deve estourar rápido (não travar o teste nem o chamador para sempre).
            assertThatThrownBy(ami::readBlock).isInstanceOf(SocketTimeoutException.class);

            ami.close();
        }
    }

    /**
     * Achado real de 2026-08-15, mais grave que o de SO_TIMEOUT=0 (coberto no teste acima): um
     * {@code docker compose restart asterisk} (SIGTERM gracioso — diferente de um restart abrupto
     * que deixaria a conexão "presa" sem FIN/RST) fecha o socket de forma limpa. Antes desta
     * correção, {@code readBlock()} devolvia string vazia nesse caso (mesmo formato de "bloco em
     * branco" válido), e o chamador ({@code CallCenterAmiEventListener}) entrava num laço apertado
     * sem nunca lançar exceção — nunca reconectava, 100% de uma CPU inteira para sempre. Este teste
     * prova que EOF antes do bloco fechar agora sempre lança {@link EOFException}.
     */
    @Test
    void readBlock_peerClosesGracefully_throwsEofExceptionInsteadOfReturningBlank() throws Exception {
        try (var server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            CompletableFuture.runAsync(
                    () -> {
                        try (Socket client = server.accept();
                                var writer =
                                        new PrintWriter(
                                                new java.io.OutputStreamWriter(
                                                        client.getOutputStream(),
                                                        StandardCharsets.UTF_8),
                                                true)) {
                            writer.print("Asterisk Call Manager/8.0.0\r\n");
                            writer.flush();
                            // Fecha a conexão de forma limpa e imediata — simula o SIGTERM
                            // gracioso de "docker compose restart asterisk".
                        } catch (IOException ignored) {
                            // Encerramento do teste — nada a fazer.
                        }
                    });

            var ami = AmiSession.connect("localhost", port, 5_000);

            assertThatThrownBy(ami::readBlock).isInstanceOf(EOFException.class);

            ami.close();
        }
    }

    @Test
    void connect_infiniteTimeout_wouldNeverThrowOnSilentPeer_documentingTheBugBeingFixed()
            throws Exception {
        // Não é um teste de comportamento correto — documenta, por contraste, por que
        // SO_TIMEOUT=0 (o valor antigo) é a causa raiz do achado real: com timeout 0, o
        // readBlock() bloqueia indefinidamente. Prova isso de forma seletiva (não travando a
        // suíte): dá um prazo curto e generoso para a leitura retornar; se ela não retornar
        // nesse prazo, é porque está bloqueando de verdade (o comportamento antigo/quebrado).
        try (var server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            CompletableFuture.runAsync(
                    () -> {
                        try (Socket client = server.accept();
                                var writer =
                                        new PrintWriter(
                                                new java.io.OutputStreamWriter(
                                                        client.getOutputStream(),
                                                        StandardCharsets.UTF_8),
                                                true)) {
                            writer.print("Asterisk Call Manager/8.0.0\r\n");
                            writer.flush();
                            Thread.sleep(3_000);
                        } catch (IOException | InterruptedException ignored) {
                            // Encerramento do teste — nada a fazer.
                        }
                    });

            var ami = AmiSession.connect("localhost", port, 0);
            var future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return ami.readBlock();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });

            assertThatThrownBy(() -> future.get(1_500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            ami.close();
        }
    }
}
