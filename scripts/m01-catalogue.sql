-- Insert-only synthetic catalogue. No accounts, existing-row updates or deletes.
-- Run only through seed-m01-catalogue.ps1 against the dedicated M0.1 project.
START TRANSACTION;
INSERT INTO brands (name,slug,description,status,created_at,updated_at)
SELECT 'DEMO Lahore Loom','demo-m01-lahore-loom','Fictional acceptance-test brand; not a signed partner. Illustrations are placeholders.','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM brands WHERE slug='demo-m01-lahore-loom');
INSERT INTO brands (name,slug,description,status,created_at,updated_at)
SELECT 'DEMO Karachi Craft','demo-m01-karachi-craft','Fictional acceptance-test brand; not a signed partner. Illustrations are placeholders.','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM brands WHERE slug='demo-m01-karachi-craft');
INSERT INTO categories (name,slug,description,status,created_at,updated_at)
SELECT 'DEMO Clothing','demo-m01-clothing','Fictional test catalogue','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE slug='demo-m01-clothing');
INSERT INTO categories (name,slug,description,status,created_at,updated_at)
SELECT 'DEMO Accessories','demo-m01-accessories','Fictional test catalogue','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE slug='demo-m01-accessories');
INSERT INTO products (name,slug,description,brand_id,category_id,price,availability,publication_status,created_at,updated_at)
SELECT sample.name,sample.slug,'Fictional demonstration product. CSS placeholder artwork; no actual stock or purchasing.',b.id,c.id,sample.price,'AVAILABLE','PUBLISHED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM (
    SELECT 'DEMO Linen Shirt' name,'demo-m01-linen-shirt' slug,'demo-m01-lahore-loom' brand,'demo-m01-clothing' category,2499.95 price
    UNION ALL SELECT 'DEMO Cotton Kurta','demo-m01-cotton-kurta','demo-m01-lahore-loom','demo-m01-clothing',3499.00
    UNION ALL SELECT 'DEMO Canvas Tote','demo-m01-canvas-tote','demo-m01-karachi-craft','demo-m01-accessories',1250.50
    UNION ALL SELECT 'DEMO Woven Scarf','demo-m01-woven-scarf','demo-m01-karachi-craft','demo-m01-accessories',1799.00
) sample
JOIN brands b ON b.slug=sample.brand
JOIN categories c ON c.slug=sample.category
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.slug=sample.slug);
COMMIT;
-- Initialize only the known fictional products. Never refill products already purchased.
UPDATE products p SET stock_quantity=20
WHERE p.slug IN ('demo-m01-linen-shirt','demo-m01-cotton-kurta','demo-m01-canvas-tote','demo-m01-woven-scarf')
AND p.stock_quantity=0
AND NOT EXISTS (SELECT 1 FROM customer_order_items oi WHERE oi.product_id=p.id);
SELECT 'demo_brands',COUNT(*) FROM brands WHERE slug IN ('demo-m01-lahore-loom','demo-m01-karachi-craft');
SELECT 'demo_products',COUNT(*) FROM products WHERE slug LIKE 'demo-m01-%';
