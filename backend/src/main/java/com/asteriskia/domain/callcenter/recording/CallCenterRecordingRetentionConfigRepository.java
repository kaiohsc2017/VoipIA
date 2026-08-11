package com.asteriskia.domain.callcenter.recording;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CallCenterRecordingRetentionConfigRepository
        extends JpaRepository<CallCenterRecordingRetentionConfig, String> {}
