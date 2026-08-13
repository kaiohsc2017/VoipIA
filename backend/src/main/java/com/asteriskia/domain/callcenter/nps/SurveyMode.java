package com.asteriskia.domain.callcenter.nps;

/**
 * SurveyMode — 4 modos de coleta de uma pesquisa de satisfação (Fase 21, D17). Escolhido na
 * criação de cada pesquisa, nunca misturado dentro da mesma pesquisa.
 */
public enum SurveyMode {
    /** 1 pergunta, dígito 0-{@code scaleMax}. Custo de IA: zero. */
    DTMF_SIMPLES,
    /** N perguntas, dígito cada. Custo de IA: zero. */
    DTMF_MULTI,
    /** Resposta falada — capturada por gravação real (ARI) e classificada/transcrita de forma
     * assíncrona (nunca durante a chamada). Custo de IA: por resposta, sempre. */
    FALADA_IA,
    /** Nota por dígito (zero custo) + comentário gravado opcional, transcrito só sob demanda
     * (D21) — nunca automaticamente. */
    DTMF_COMENTARIO
}
