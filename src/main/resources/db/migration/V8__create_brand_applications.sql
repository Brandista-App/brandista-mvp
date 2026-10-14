CREATE TABLE brand_applications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    brand_name VARCHAR(150) NOT NULL,
    contact_name VARCHAR(150) NOT NULL,
    contact_email VARCHAR(254) NOT NULL,
    website VARCHAR(2048) NULL,
    message VARCHAR(2000) NULL,
    status VARCHAR(24) NOT NULL,
    review_note VARCHAR(2000) NULL,
    reviewed_at DATETIME(6) NULL,
    brand_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_brand_applications PRIMARY KEY (id),
    CONSTRAINT ck_brand_applications_status CHECK (status IN ('SUBMITTED', 'NEEDS_INFORMATION', 'APPROVED', 'REJECTED')),
    CONSTRAINT fk_brand_applications_brand FOREIGN KEY (brand_id) REFERENCES brands (id) ON DELETE RESTRICT
);

CREATE INDEX idx_brand_applications_status_created ON brand_applications (status, created_at);
CREATE INDEX idx_brand_applications_contact_email ON brand_applications (contact_email);
