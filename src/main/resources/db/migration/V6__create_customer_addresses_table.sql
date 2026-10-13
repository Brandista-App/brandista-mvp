CREATE TABLE customer_addresses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    recipient_name VARCHAR(120) NOT NULL,
    phone_number VARCHAR(25) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255) NULL,
    city VARCHAR(100) NOT NULL,
    province VARCHAR(100) NULL,
    postal_code VARCHAR(20) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_customer_addresses PRIMARY KEY (id),
    CONSTRAINT fk_customer_addresses_user FOREIGN KEY (customer_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_customer_addresses_customer_created ON customer_addresses (customer_id, created_at, id);
