package com.epicit.pos.service;

import com.epicit.pos.domain.AuditLog;
import com.epicit.pos.repo.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes owner actions to the append-only audit log (NFR-8). Joins the caller's transaction so the audit row
 * and the change it describes commit or roll back together. Never pass a PIN or hash as a value.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entity, String oldValue, String newValue, String reason) {
        repository.save(new AuditLog(action, entity, oldValue, newValue, reason));
    }
}
