CREATE TABLE brand_admin_invitations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    brand_id BIGINT NOT NULL,
    recipient_email VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    accepted_at DATETIME(6) NULL,
    accepted_user_id BIGINT NULL,
    membership_id BIGINT NULL,
    expected_membership_version BIGINT NULL,
    accepted_membership_version BIGINT NULL,
    replaces_invitation_id BIGINT NULL,
    CONSTRAINT uk_brand_invitation_hash UNIQUE(token_hash),
    CONSTRAINT fk_invitation_application FOREIGN KEY(application_id) REFERENCES brand_applications(id),
    CONSTRAINT fk_invitation_brand FOREIGN KEY(brand_id) REFERENCES brands(id),
    CONSTRAINT fk_invitation_creator FOREIGN KEY(created_by) REFERENCES users(id),
    CONSTRAINT fk_invitation_acceptor FOREIGN KEY(accepted_user_id) REFERENCES users(id),
    CONSTRAINT fk_invitation_membership FOREIGN KEY(membership_id) REFERENCES brand_admin_memberships(id),
    CONSTRAINT fk_invitation_replacement FOREIGN KEY(replaces_invitation_id) REFERENCES brand_admin_invitations(id)
);
CREATE INDEX ix_invitation_application ON brand_admin_invitations(application_id,id);
CREATE INDEX ix_invitation_brand ON brand_admin_invitations(brand_id,accepted_at);
CREATE TABLE brand_invitation_accept_limits (
    user_id BIGINT NOT NULL PRIMARY KEY,
    window_started_at DATETIME(6) NOT NULL,
    attempt_count INT NOT NULL,
    CONSTRAINT fk_accept_limit_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
ALTER TABLE security_audit_events ADD COLUMN target_invitation_id BIGINT NULL;
ALTER TABLE security_audit_events ADD COLUMN target_application_id BIGINT NULL;
