-- Explicit grants only: no legacy ADMIN/customer backfill and no account changes.
CREATE TABLE brand_admin_memberships (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    brand_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_brand_membership_user_brand UNIQUE (user_id, brand_id),
    CONSTRAINT fk_brand_membership_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_brand_membership_brand FOREIGN KEY (brand_id) REFERENCES brands(id) ON DELETE RESTRICT
);
CREATE INDEX ix_brand_membership_brand_active ON brand_admin_memberships(brand_id, revoked_at, user_id);
-- Keep old audit records intact; membership events identify both user and tenant.
ALTER TABLE security_audit_events ADD COLUMN target_brand_id BIGINT NULL;
