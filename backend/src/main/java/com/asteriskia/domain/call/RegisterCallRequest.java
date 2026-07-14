package com.asteriskia.domain.call;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Request do agente Python para registrar a chamada. */
public record RegisterCallRequest(
        @NotBlank String callUuid,
        Integer uraId, // qual URA conduziu a chamada — null = URA legada (id=1)
        Map<String, String> fields,
        String audioFilePath, // caminho do .wav gravado pelo agente Python
        String transcription, // transcrição completa consolidada
        String callerNumber, // número do chamador (CALLERID do Asterisk)
        Integer callDurationSecs, // duração total da chamada em segundos
        String subjectTag // assunto classificado por IA (best-effort, pode vir null)
        ) {}
