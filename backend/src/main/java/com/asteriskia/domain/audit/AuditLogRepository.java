package com.asteriskia.domain.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /** Últimos N registros de login (bem-sucedidos ou não). */
    List<AuditLog> findByActionInOrderByCreatedAtDesc(List<String> actions, Pageable pageable);
}
