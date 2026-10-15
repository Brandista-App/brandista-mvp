ALTER TABLE customer_addresses ADD COLUMN delivery_instructions VARCHAR(500) NULL;
ALTER TABLE users ADD COLUMN default_address_id BIGINT NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_default_address FOREIGN KEY (default_address_id) REFERENCES customer_addresses(id) ON DELETE SET NULL;
ALTER TABLE products ADD COLUMN stock_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE products ADD CONSTRAINT ck_products_stock CHECK (stock_quantity >= 0);

CREATE TABLE customer_orders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    source_address_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    payment_method VARCHAR(16) NOT NULL,
    payment_status VARCHAR(16) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    subtotal DECIMAL(19,2) NOT NULL,
    delivery_charge DECIMAL(19,2) NOT NULL,
    tax DECIMAL(19,2) NOT NULL,
    discount DECIMAL(19,2) NOT NULL,
    total DECIMAL(19,2) NOT NULL,
    recipient_name VARCHAR(120) NOT NULL,
    phone_number VARCHAR(25) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255) NULL,
    city VARCHAR(100) NOT NULL,
    province VARCHAR(100) NULL,
    postal_code VARCHAR(20) NULL,
    delivery_instructions VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_customer_order_key UNIQUE (customer_id,idempotency_key),
    CONSTRAINT fk_customer_order_user FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT ck_customer_order_status CHECK (status='PLACED'),
    CONSTRAINT ck_customer_order_payment CHECK (payment_method='COD' AND payment_status='UNPAID'),
    CONSTRAINT ck_customer_order_money CHECK (subtotal>=0 AND delivery_charge=0 AND tax=0 AND discount=0 AND total=subtotal AND currency='PKR')
);
CREATE INDEX idx_customer_orders_created ON customer_orders(customer_id,created_at,id);
CREATE TABLE customer_order_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_slug VARCHAR(160) NOT NULL,
    product_name VARCHAR(150) NOT NULL,
    brand_id BIGINT NOT NULL,
    brand_name VARCHAR(150) NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    line_subtotal DECIMAL(19,2) NOT NULL,
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES customer_orders(id),
    CONSTRAINT ck_order_item_values CHECK (quantity>0 AND unit_price>=0 AND line_subtotal>=0)
);
