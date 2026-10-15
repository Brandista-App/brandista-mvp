-- Read-only, synthetic M0.1 accounts only. Never print passwords or hashes.
SELECT 'database',DATABASE(),VERSION();
SELECT 'migration',version,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT 'catalogue',b.name,p.name,p.price,p.availability,p.publication_status
FROM products p JOIN brands b ON b.id=p.brand_id WHERE p.slug LIKE 'demo-m01-%' ORDER BY p.id;
SELECT 'customer',id,email,role,status,
    (LENGTH(password_hash)=60 AND LEFT(password_hash,1)=CHAR(36) AND SUBSTRING(password_hash,2,1)='2') AS bcrypt_format
FROM users WHERE email LIKE 'm01-%@example.test' ORDER BY id;
SELECT 'privileged_accounts',COUNT(*) FROM users WHERE role='ADMIN';
SELECT 'cart_row',u.id,u.email,c.id,p.slug,b.slug,ci.quantity,p.price,(ci.quantity*p.price) AS line_subtotal
FROM users u JOIN carts c ON c.customer_id=u.id JOIN cart_items ci ON ci.cart_id=c.id
JOIN products p ON p.id=ci.product_id JOIN brands b ON b.id=p.brand_id
WHERE u.email LIKE 'm01-%@example.test' ORDER BY u.id,ci.id;
SELECT 'cart_total',u.id,COUNT(ci.id),SUM(ci.quantity*p.price)
FROM users u JOIN carts c ON c.customer_id=u.id JOIN cart_items ci ON ci.cart_id=c.id JOIN products p ON p.id=ci.product_id
WHERE u.email LIKE 'm01-%@example.test' GROUP BY u.id;
SELECT 'order_tables',COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('orders','order_items');
