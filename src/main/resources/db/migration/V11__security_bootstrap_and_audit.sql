-- A permanent singleton serializes operator provisioning and security mutations.
-- Never delete/reset this row to repeat bootstrap. Recovery is a separate operation.
CREATE TABLE security_provisioning_state (
    id INT NOT NULL PRIMARY KEY,
    initialized_at DATETIME(6) NULL,
    CONSTRAINT ck_security_state_singleton CHECK (id = 1)
);
INSERT INTO security_provisioning_state(id) VALUES (1);

-- No cascading FKs: historical actors/targets survive account lifecycle changes.
-- Runtime access is append/read only; use a separate DBA identity for retention.
CREATE TABLE security_audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor VARCHAR(160) NOT NULL,
    operation VARCHAR(48) NOT NULL,
    target_user_id BIGINT NULL,
    occurred_at DATETIME(6) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason VARCHAR(48) NOT NULL
);
CREATE INDEX ix_security_audit_time ON security_audit_events(occurred_at, id);
CREATE INDEX ix_security_audit_target ON security_audit_events(target_user_id, id);
