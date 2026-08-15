package com.asteriskia.domain.callcenter.identity;

/**
 * IdentitySource — como um contato foi identificado contra {@code ad_users} (Fase 14 do plano
 * Call Center — decisões D7/D8). {@code UNRESOLVED} nunca é persistido em
 * {@code cc_interactions}/{@code cc_chat_sessions}: ausência de linha já significa isso — existe
 * só como valor de retorno de {@link CallCenterIdentityResolver} para o chamador decidir o que
 * fazer (nunca lança exceção).
 */
public enum IdentitySource {
    /** Login de rede do usuário já autenticado por JWT (chat interno). */
    NETWORK_LOGIN,
    /** Informado pelo próprio contato — digitado (widget) ou falado + confirmado (URA de voz). */
    URA_INPUT,
    /** Resolvido só pelo número de origem (ANI/telefone), sem confirmação ativa do contato. */
    ANI,
    /** Nenhuma das vias anteriores encontrou/confirmou um contato — estado normal, não é erro. */
    UNRESOLVED
}
