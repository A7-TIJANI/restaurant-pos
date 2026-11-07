package com.epicit.pos.repo;

import com.epicit.pos.domain.AuditLog;
import org.springframework.data.repository.Repository;

/**
 * Deliberately NOT a JpaRepository: the audit log is append-only (NFR-7), so the type exposes
 * only save and read operations - no delete, no bulk update.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog entry);

    long count();

    Iterable<AuditLog> findAll();
}
