-- 购物车表新增 version 字段，用于 Redis-MySQL 补偿同步版本比对
ALTER TABLE cart ADD COLUMN version BIGINT DEFAULT NULL COMMENT '版本号(时间戳),用于Redis-MySQL补偿同步比对';
CREATE INDEX idx_cart_user_version ON cart(user_id, version);
