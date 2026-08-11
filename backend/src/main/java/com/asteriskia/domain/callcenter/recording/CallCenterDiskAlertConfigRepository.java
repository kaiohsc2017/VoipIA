package com.asteriskia.domain.callcenter.recording;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CallCenterDiskAlertConfigRepository
        extends JpaRepository<CallCenterDiskAlertConfig, String> {}
