package com.asteriskia.integration.ami;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Predicate;

/**
 * AmiSession — sessão TCP única com o AMI (Asterisk Manager Interface), extraída da duplicação de
 * protocolo (O2.2 do roadmap de refatoração) presente em AsteriskAmiClient, StatsTrunkAmiClient,
 * AmiOriginateService e AsteriskConfigController.amiReload. Encapsula só o protocolo raw (conectar,
 * login, enviar ação, ler bloco/até sentinela, logoff) — cada chamador mantém seu próprio parsing
 * de resposta e tratamento de erro, que já divergia entre os 4 usos originais.
 */
public class AmiSession implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    private AmiSession(Socket socket, BufferedReader reader, PrintWriter writer) {
        this.socket = socket;
        this.reader = reader;
        this.writer = writer;
    }

    /** Abre a conexão TCP e consome o banner de boas-vindas do AMI. */
    public static AmiSession connect(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket(host, port);
        try {
            socket.setSoTimeout(timeoutMs);
            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer =
                    new PrintWriter(
                            new OutputStreamWriter(
                                    socket.getOutputStream(), StandardCharsets.UTF_8),
                            true);
            reader.readLine(); // banner
            return new AmiSession(socket, reader, writer);
        } catch (IOException e) {
            // Fecha o socket antes de propagar — sem isso, uma falha aqui (ex: timeout no
            // banner) vazaria o file descriptor, já que o chamador só recebe (e fecha via
            // try-with-resources) uma instância de AmiSession que nunca chegou a existir.
            socket.close();
            throw e;
        }
    }

    /** Autentica via Action: Login. */
    public boolean login(String user, String password) throws IOException {
        send(Map.of("Action", "Login", "Username", user, "Secret", password));
        return readBlock().contains("Success");
    }

    /** Envia um bloco de ação AMI (pares chave:valor + linha em branco final). */
    public void send(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        writer.print(sb);
        writer.flush();
    }

    /** Lê linhas até encontrar a linha em branco que fecha o bloco AMI.
     *
     * <p>Achado real de 2026-08-15 (validação com tráfego real de fila, mais grave que o problema
     * de SO_TIMEOUT=0 corrigido antes neste mesmo dia): quando o peer fecha a conexão de forma
     * graciosa (ex.: {@code docker compose restart asterisk}, que envia SIGTERM e deixa o processo
     * do Asterisk fechar seus sockets normalmente — diferente de um restart abrupto que deixaria a
     * conexão "presa"), {@code reader.readLine()} retorna {@code null} imediatamente (EOF), sem
     * nunca bloquear e sem nunca lançar exceção. O código antigo tratava isso como "bloco vazio" e
     * devolvia string vazia — o chamador ({@code CallCenterAmiEventListener.connectAndConsume})
     * então via um bloco em branco, dava {@code continue} e chamava {@code readBlock()} de novo no
     * mesmo socket já fechado, entrando num laço apertado sem nenhuma espera: 100% de uma CPU
     * inteira, para sempre, sem nunca reconectar — pior que o caso de SO_TIMEOUT, porque nem
     * timeout ajuda aqui (não há bloqueio algum a estourar). Corrigido lançando {@link EOFException}
     * quando o EOF é atingido antes da linha em branco — isso propaga como {@link IOException} pro
     * mesmo caminho de reconexão que já existia, tanto para EOF na primeira linha quanto no meio de
     * um bloco parcial (nenhum dos dois é um bloco AMI válido). */
    public String readBlock() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break;
            sb.append(line).append("\n");
        }
        if (line == null) {
            throw new EOFException("Conexão AMI encerrada pelo peer (EOF) antes do bloco fechar.");
        }
        return sb.toString();
    }

    /**
     * Lê linhas até que uma delas satisfaça a sentinela (inclusive), sem exigir linha em branco.
     *
     * <p>Mesma correção de EOF de {@link #readBlock()} (achado real de 2026-08-15) — sem ela, um
     * EOF antes da sentinela devolveria silenciosamente uma resposta truncada em vez de sinalizar
     * a conexão quebrada.
     */
    public String readUntil(Predicate<String> sentinel) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
            if (sentinel.test(line)) break;
        }
        if (line == null) {
            throw new EOFException("Conexão AMI encerrada pelo peer (EOF) antes da sentinela.");
        }
        return sb.toString();
    }

    public void logoff() {
        send(Map.of("Action", "Logoff"));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
