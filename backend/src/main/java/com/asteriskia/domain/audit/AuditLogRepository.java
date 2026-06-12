package com.asteriskia.domain.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a " +
           "WHERE (:username IS NULL OR a.username = :username) " +
           "AND   (:action   IS NULL OR a.action   = :action) " +
           "AND   (:from     IS NULL OR a.createdAt >= :from) " +
           "AND   (:to       IS NULL OR a.createdAt <= :to) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> findWithFilters(
            @Param("username") String username,
            @Param("action")   String action,
            @Param("from")     LocalDateTime from,
            @Param("to")       LocalDateTime to,
            Pageable pageable);

    /** Últimos N registros de login (bem-sucedidos ou não). */
    List<AuditLog> findByActionInOrderByCreatedAtDesc(List<String> actions, Pageable pageable);
}
