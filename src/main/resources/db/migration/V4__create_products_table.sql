CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(160) NOT NULL,
    description VARCHAR(4000) NULL,
    brand_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    availability VARCHAR(16) NOT NULL,
    publication_status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uk_products_slug UNIQUE (slug),
    CONSTRAINT ck_products_price CHECK (price >= 0),
    CONSTRAINT ck_products_availability CHECK (availability IN ('AVAILABLE', 'UNAVAILABLE')),
    CONSTRAINT ck_products_publication_status CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'DEACTIVATED')),
    CONSTRAINT fk_products_brand FOREIGN KEY (brand_id) REFERENCES brands (id) ON DELETE RESTRICT,
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT
);

CREATE INDEX idx_products_public_name ON products (publication_status, name);
CREATE INDEX idx_products_brand_id ON products (brand_id);
CREATE INDEX idx_products_category_id ON products (category_id);
