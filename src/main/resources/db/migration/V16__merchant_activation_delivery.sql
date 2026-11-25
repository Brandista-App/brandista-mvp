ALTER TABLE brand_admin_invitations ADD COLUMN delivery_nonce VARCHAR(64) NULL;
ALTER TABLE brand_admin_invitations ADD COLUMN terms_version VARCHAR(64) NULL;
ALTER TABLE brand_admin_invitations ADD COLUMN terms_accepted_at DATETIME(6) NULL;
CREATE TABLE merchant_invitation_delivery (
 invitation_id BIGINT NOT NULL PRIMARY KEY,
 state VARCHAR(32) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL,
 first_attempt_at DATETIME(6) NULL,
 lease_until DATETIME(6) NULL,
 provider_id VARCHAR(128) NULL,
 error_code VARCHAR(64) NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_merchant_delivery_invitation FOREIGN KEY(invitation_id) REFERENCES brand_admin_invitations(id)
);
CREATE INDEX ix_merchant_delivery_due ON merchant_invitation_delivery(state,next_attempt_at);
CREATE TABLE merchant_registration_limits (
 bucket_hash VARCHAR(64) NOT NULL PRIMARY KEY,
 window_started_at DATETIME(6) NOT NULL,
 attempts INT NOT NULL
);
