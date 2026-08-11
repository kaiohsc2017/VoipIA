package com.asteriskia.domain.security;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RegexTester — roda uma regex do usuário contra um conjunto de linhas de log com timeout, extraído
 * de SecurityController (fase 7 da refatoração).
 *
 * <p>Achado de segurança (ReDoS, low): regex vinda do cliente rodava sem timeout — uma regex com
 * catastrophic backtracking travava a thread indefinidamente (endpoint é admin-only, então o
 * impacto é auto-DoS, mas ainda vale limitar). Roda numa thread dedicada e interrompível; envolve
 * cada linha numa CharSequence que verifica a interrupção a cada charAt(), já que o motor de regex
 * do Java não respeita Thread.interrupt() sozinho.
 */
public final class RegexTester {

    private RegexTester() {}

    private static final int TIMEOUT_SECONDS = 2;
    private static final int MAX_MATCHES = 20;

    public static List<String> runWithTimeout(Pattern p, List<String> log) throws TimeoutException {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future =
                    executor.submit(
                            () ->
                                    log.stream()
                                            .filter(
                                                    l ->
                                                            p.matcher(
                                                                            new InterruptibleCharSequence(
                                                                                    l))
                                                                    .find())
                                            .limit(MAX_MATCHES)
                                            .collect(Collectors.toList()));
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence inner;

        InterruptibleCharSequence(CharSequence inner) {
            this.inner = inner;
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted())
                throw new RuntimeException("Regex interrompida por timeout");
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(inner.subSequence(start, end));
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
