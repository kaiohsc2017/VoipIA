package com.asteriskia.domain.callcenter.flow.audio;

import java.time.LocalDateTime;

public record CcAudioFileDto(
        Long id, String name, String fileName, Integer durationSeconds, Long businessUnitId, LocalDateTime createdAt) {

    public static CcAudioFileDto from(CcAudioFile entity) {
        return new CcAudioFileDto(
                entity.getId(),
                entity.getName(),
                entity.getFileName(),
                entity.getDurationSeconds(),
                entity.getBusinessUnit() == null ? null : entity.getBusinessUnit().getId().longValue(),
                entity.getCreatedAt());
    }
}
