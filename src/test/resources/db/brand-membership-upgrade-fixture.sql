-- Synthetic migration preservation fixture, never an operator provisioning script.
INSERT INTO users(id,email,password_hash,role,status,created_at,updated_at) VALUES
(1,'customer@example.invalid','synthetic-not-a-password-hash','CUSTOMER','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(2,'legacy@example.invalid','synthetic-not-a-password-hash','ADMIN','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO brands(id,name,slug,status,created_at,updated_at) VALUES
(1,'Synthetic A','synthetic-a','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(2,'Synthetic B','synthetic-b','INACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO categories(id,name,slug,status,created_at,updated_at) VALUES
(1,'Synthetic category','synthetic-category','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO products(id,name,slug,brand_id,category_id,price,availability,publication_status,stock_quantity,created_at,updated_at)
VALUES(1,'Synthetic product','synthetic-product',1,1,150.00,'AVAILABLE','PUBLISHED',9,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO customer_addresses(id,customer_id,recipient_name,phone_number,address_line1,city,created_at,updated_at)
VALUES(1,1,'Synthetic recipient','0000000000','Synthetic street','Synthetic city',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
UPDATE users SET default_address_id=1 WHERE id=1;
INSERT INTO carts(id,customer_id,created_at,updated_at) VALUES(1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO cart_items(id,cart_id,product_id,quantity,created_at,updated_at) VALUES(1,1,1,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO customer_orders(id,customer_id,idempotency_key,request_hash,source_address_id,status,payment_method,payment_status,currency,
subtotal,delivery_charge,tax,discount,total,recipient_name,phone_number,address_line1,city,created_at)
VALUES(1,1,'synthetic-upgrade-key','synthetic-request-hash',1,'PLACED','COD','UNPAID','PKR',150.00,0,0,0,150.00,
'Synthetic recipient','0000000000','Synthetic street','Synthetic city',CURRENT_TIMESTAMP);
INSERT INTO customer_order_items(id,order_id,product_id,product_slug,product_name,brand_id,brand_name,quantity,unit_price,line_subtotal)
VALUES(1,1,1,'synthetic-product','Synthetic product',1,'Synthetic A',1,150.00,150.00);
INSERT INTO brand_applications(id,brand_name,contact_name,contact_email,status,brand_id,created_at,updated_at)
VALUES(1,'Synthetic A','Synthetic contact','contact@example.invalid','APPROVED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO user_platform_grants(id,user_id,grant_type,created_at) VALUES(1,2,'SUPER_ADMIN',CURRENT_TIMESTAMP);
INSERT INTO security_audit_events(actor,operation,target_user_id,occurred_at,outcome,reason)
VALUES('process:synthetic','INITIAL_GRANT',2,CURRENT_TIMESTAMP,'SUCCESS','COMPLETED');
