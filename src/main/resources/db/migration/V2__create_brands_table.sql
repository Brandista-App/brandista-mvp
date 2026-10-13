CREATE TABLE brands (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(160) NOT NULL,
    description VARCHAR(2000) NULL,
    logo_reference VARCHAR(2048) NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_brands PRIMARY KEY (id),
    CONSTRAINT uk_brands_slug UNIQUE (slug),
    CONSTRAINT ck_brands_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_brands_status_name ON brands (status, name);
