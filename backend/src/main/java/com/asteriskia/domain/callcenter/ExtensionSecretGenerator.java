package com.asteriskia.domain.callcenter;

import java.security.SecureRandom;

/** ExtensionSecretGenerator — senha aleatória para o auth SIP do ramal provisionado via ARA. */
final class ExtensionSecretGenerator {

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%";
    private static final int LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ExtensionSecretGenerator() {}

    static String generate() {
        var sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
