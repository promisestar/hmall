-- ============================================================
-- hmall 秒杀模块建表 SQL
-- 对应 redis-application-analysis.md 3.7.1 节
-- ============================================================

-- 秒杀活动表（eg: "618 专场"）
CREATE TABLE IF NOT EXISTS `seckill_promotion` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `title`      VARCHAR(128) NOT NULL COMMENT '活动标题',
  `start_date` DATE         NOT NULL COMMENT '活动开始日期',
  `end_date`   DATE         NOT NULL COMMENT '活动结束日期',
  `status`     INT          DEFAULT 0 COMMENT '状态: 0未开始 1进行中 2已结束',
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- 秒杀场次表（eg: 10:00-12:00）
CREATE TABLE IF NOT EXISTS `seckill_session` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `promotion_id` BIGINT      NOT NULL COMMENT '关联活动ID',
  `name`        VARCHAR(64)  NOT NULL COMMENT '场次名称',
  `start_time`  DATETIME     NOT NULL COMMENT '场次开始时间',
  `end_time`    DATETIME     NOT NULL COMMENT '场次结束时间',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_promotion` (`promotion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀场次表';

-- 活动-商品关联表（含秒杀价+库存+限购数）
CREATE TABLE IF NOT EXISTS `seckill_product_relation` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `promotion_id`  BIGINT      NOT NULL COMMENT '活动ID',
  `session_id`    BIGINT      NOT NULL COMMENT '场次ID',
  `product_id`    BIGINT      NOT NULL COMMENT '商品ID（item表）',
  `seckill_price` INT         NOT NULL COMMENT '秒杀价（分）',
  `stock`         INT         NOT NULL COMMENT '秒杀总库存',
  `limit_num`     INT         DEFAULT 1 COMMENT '每人限购数量',
  `create_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动-商品关联表';

-- 每日库存快照表（底层防超卖行锁目标）
CREATE TABLE IF NOT EXISTS `seckill_daily_stock` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `relation_id` BIGINT   NOT NULL COMMENT '关联seckill_product_relation.id',
  `batch_date`  DATE     NOT NULL COMMENT '批次日期',
  `stock`       INT      NOT NULL COMMENT '当日剩余库存',
  `sold`        INT      DEFAULT 0 COMMENT '已售数量',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relation_date` (`relation_id`, `batch_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀每日库存快照表';

-- 秒杀订单关联表（追踪秒杀订单，用于超时回补）
CREATE TABLE IF NOT EXISTS `seckill_order` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT   NOT NULL COMMENT '订单ID（order表）',
  `relation_id` BIGINT   NOT NULL COMMENT '关联seckill_product_relation.id',
  `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
  `quantity`    INT      NOT NULL COMMENT '购买数量',
  `status`      INT      DEFAULT 1 COMMENT '状态: 1待支付 2已支付 3已关闭',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`),
  KEY `idx_relation` (`relation_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单关联表';
