package com.asteriskia.domain.insights;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UploadBatchRepository extends JpaRepository<UploadBatch, UUID> {

    Page<UploadBatch> findByUploadedByOrderByCreatedAtDesc(String uploadedBy, Pageable pageable);

    Page<UploadBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
