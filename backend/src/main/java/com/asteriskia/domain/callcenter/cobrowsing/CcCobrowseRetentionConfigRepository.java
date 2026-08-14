package com.asteriskia.domain.callcenter.cobrowsing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcCobrowseRetentionConfigRepository
        extends JpaRepository<CcCobrowseRetentionConfig, String> {}
