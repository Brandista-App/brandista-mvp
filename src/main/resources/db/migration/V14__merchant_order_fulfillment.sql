-- Fulfillment is per immutable order/brand snapshot, never per mutable product owner.
-- Customer order/payment/money rows and inventory remain unchanged.
CREATE TABLE merchant_order_fulfillments (
    order_id BIGINT NOT NULL,
    brand_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id, brand_id),
    CONSTRAINT fk_merchant_fulfillment_order FOREIGN KEY (order_id) REFERENCES customer_orders(id),
    CONSTRAINT ck_merchant_fulfillment_status CHECK (status IN ('PLACED','ACCEPTED','PACKED','SHIPPED','DELIVERED')),
    CONSTRAINT ck_merchant_fulfillment_version CHECK (version >= 0)
);
CREATE INDEX ix_merchant_fulfillment_brand_status ON merchant_order_fulfillments(brand_id,status,order_id);
CREATE INDEX ix_order_items_brand_order ON customer_order_items(brand_id,order_id,id);

-- Existing orders only ever supported PLACED. No invented historical transitions.
INSERT INTO merchant_order_fulfillments(order_id,brand_id,status,version,created_at,updated_at)
SELECT DISTINCT o.id,i.brand_id,'PLACED',0,o.created_at,o.created_at
FROM customer_orders o JOIN customer_order_items i ON i.order_id=o.id;

-- Insert-only application audit/timeline. No PII, secrets, payment details or free text.
CREATE TABLE merchant_order_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    brand_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    from_status VARCHAR(24) NOT NULL,
    to_status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_merchant_order_event_version UNIQUE(order_id,brand_id,version),
    CONSTRAINT fk_merchant_order_event_fulfillment FOREIGN KEY (order_id,brand_id)
        REFERENCES merchant_order_fulfillments(order_id,brand_id),
    CONSTRAINT ck_merchant_order_event_actor CHECK (actor_user_id>0 AND version>0),
    CONSTRAINT ck_merchant_order_event_transition CHECK (
        (from_status='PLACED' AND to_status='ACCEPTED') OR
        (from_status='ACCEPTED' AND to_status='PACKED') OR
        (from_status='PACKED' AND to_status='SHIPPED') OR
        (from_status='SHIPPED' AND to_status='DELIVERED'))
);
