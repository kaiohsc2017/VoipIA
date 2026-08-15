package com.asteriskia.domain.callcenter.identity;

import com.asteriskia.integration.ad.AdUser;

/**
 * ResolvedIdentity — resultado de {@link CallCenterIdentityResolver} quando um contato é
 * identificado com sucesso. {@code confidence} só é relevante para {@link IdentitySource#URA_INPUT}
 * obtido por busca aproximada (STT + trigram) — {@code null} para os demais casos (correspondência
 * exata, sem ambiguidade a reportar).
 */
public record ResolvedIdentity(AdUser adUser, IdentitySource source, Double confidence) {

    public static ResolvedIdentity exact(AdUser adUser, IdentitySource source) {
        return new ResolvedIdentity(adUser, source, null);
    }

    public static ResolvedIdentity fuzzy(AdUser adUser, double confidence) {
        return new ResolvedIdentity(adUser, IdentitySource.URA_INPUT, confidence);
    }
}
