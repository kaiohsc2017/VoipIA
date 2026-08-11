package com.asteriskia.domain.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * SecurityFileUtils — utilitários pequenos e sem estado reusados por
 * {@link FailToBanClient}, {@link JailConfigRepository}, {@link AsteriskAclService}
 * e {@link SecurityController} (extraídos na refatoração que quebrou o antigo
 * SecurityController monolítico de ~880 linhas nessas 4 classes).
 */
final class SecurityFileUtils {

    private SecurityFileUtils() {}

    /** Escreve um arquivo de forma atômica (write em .tmp -> rename). */
    static void writeAtomic(Path path, String content) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
