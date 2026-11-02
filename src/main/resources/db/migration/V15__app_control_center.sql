-- Independent content records; no existing commerce or identity rows are changed.
CREATE TABLE app_content (
 id INTEGER PRIMARY KEY,
 version BIGINT NOT NULL,
 draft MEDIUMTEXT NOT NULL,
 published MEDIUMTEXT NULL
);
INSERT INTO app_content(id,version,draft) VALUES(1,0,'{"banners":[],"sections":[{"key":"HERO","enabled":true},{"key":"COLLECTIONS","enabled":true},{"key":"BRANDS","enabled":true},{"key":"PRODUCTS","enabled":true}],"brandIds":[],"collectionIds":[]}');
CREATE TABLE app_content_history (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 version BIGINT NOT NULL,
 action VARCHAR(24) NOT NULL,
 actor VARCHAR(100) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 snapshot MEDIUMTEXT NOT NULL
);
CREATE TABLE app_content_images (
 url VARCHAR(700) NOT NULL PRIMARY KEY,
 actor VARCHAR(100) NOT NULL,
 created_at DATETIME(6) NOT NULL
);
