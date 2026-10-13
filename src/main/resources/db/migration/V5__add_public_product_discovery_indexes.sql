CREATE INDEX idx_products_public_price ON products (publication_status, price, id);
CREATE INDEX idx_products_public_brand ON products (publication_status, brand_id);
CREATE INDEX idx_products_public_category ON products (publication_status, category_id);
