package com.epicit.pos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only record of owner actions. No setters, and no repository method deletes rows. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false, length = 20, updatable = false)
    private String actor = "OWNER";

    @Column(nullable = false, length = 40, updatable = false)
    private String action;

    @Column(length = 60, updatable = false)
    private String entity;

    @Lob
    @Column(name = "old_value", updatable = false)
    private String oldValue;

    @Lob
    @Column(name = "new_value", updatable = false)
    private String newValue;

    @Column(updatable = false)
    private String reason;

    protected AuditLog() {
    }

    public AuditLog(String action, String entity, String oldValue, String newValue, String reason) {
        this.action = action;
        this.entity = entity;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getEntity() { return entity; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getReason() { return reason; }
}
