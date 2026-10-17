-- No INSERT/UPDATE of users or automatic grant backfill: legacy ADMIN is not SUPER_ADMIN.
CREATE TABLE user_platform_grants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    grant_type VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_platform_grants PRIMARY KEY (id),
    CONSTRAINT uk_platform_grant_user_type UNIQUE (user_id, grant_type),
    CONSTRAINT fk_platform_grant_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_grant_type CHECK (grant_type IN ('SUPER_ADMIN'))
);
