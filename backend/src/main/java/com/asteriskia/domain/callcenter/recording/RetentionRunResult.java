package com.asteriskia.domain.callcenter.recording;

/** RetentionRunResult — resultado de uma execução (agendada ou manual) do expurgo de retenção. */
public record RetentionRunResult(int deletedCount) {}
