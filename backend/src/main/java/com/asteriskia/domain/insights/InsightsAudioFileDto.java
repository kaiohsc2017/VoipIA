package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/**
 * InsightsAudioFileDto — substitui o CallAudioFile cru no detalhe (V43): grupo
 * A/B sempre visíveis, grupo C nulificado para não-ADMIN, e SEM xml_raw (que
 * antes trafegava pra qualquer usuário — ganho colateral de segurança desta
 * entrega, ver plano insights-chamadas-campos-xml).
 */
public record InsightsAudioFileDto(
        Long id,
        String callRef,
        Integer durationSeconds,
        LocalDateTime callStarttime,
        String agentName,
        String agentIdVerint,
        String extension,
        String ani,
        String dnis,
        String direction,
        String skill,
        String status,
        String errorMsg,
        // Grupo A — Identificação
        String customerNumber,
        String organization,
        // Grupo B — Qualidade
        String disconnectedBy,
        Integer numberOfHolds,
        Integer totalHoldTime,
        Integer numberOfTransfers,
        Integer numberOfConferences,
        Integer wrapupTime,
        // Grupo C — Técnico/Auditoria — null quando !isAdmin
        String codec,
        Integer missedRtpPackets,
        Integer decodingErrors,
        String switchCallId,
        String trunk,
        String captureType,
        String datasourceName
) {
    public static InsightsAudioFileDto from(CallAudioFile audioFile, boolean isAdmin) {
        return new InsightsAudioFileDto(
                audioFile.getId(),
                audioFile.getCallRef(),
                audioFile.getDurationSeconds(),
                audioFile.getCallStarttime(),
                audioFile.getAgentName(),
                audioFile.getAgentIdVerint(),
                audioFile.getExtension(),
                resolveDisplayAni(audioFile),
                audioFile.getDnis(),
                audioFile.getDirection(),
                audioFile.getSkill(),
                audioFile.getStatus(),
                audioFile.getErrorMsg(),
                audioFile.getCustomerNumber(),
                audioFile.getOrganization(),
                audioFile.getDisconnectedBy(),
                audioFile.getNumberOfHolds(),
                audioFile.getTotalHoldTime(),
                audioFile.getNumberOfTransfers(),
                audioFile.getNumberOfConferences(),
                audioFile.getWrapupTime(),
                isAdmin ? audioFile.getCodec() : null,
                isAdmin ? audioFile.getMissedRtpPackets() : null,
                isAdmin ? audioFile.getDecodingErrors() : null,
                isAdmin ? audioFile.getSwitchCallId() : null,
                isAdmin ? audioFile.getTrunk() : null,
                isAdmin ? audioFile.getCaptureType() : null,
                isAdmin ? audioFile.getDatasourceName() : null
        );
    }

    /**
     * Decisão 9 do plano: em chamadas outbound (efetuadas), o session/ani bruto do XML
     * é o ramal do próprio atendente (quem originou), não o número do cliente — exibir
     * o dnis bruto (número discado) evita confundir o usuário. Regra de EXIBIÇÃO só:
     * ani/dnis brutos continuam intactos em CallAudioFile/xml_raw.
     */
    public static String resolveDisplayAni(CallAudioFile audioFile) {
        if ("outbound".equalsIgnoreCase(audioFile.getDirection())) {
            return audioFile.getDnis();
        }
        return audioFile.getAni();
    }
}
