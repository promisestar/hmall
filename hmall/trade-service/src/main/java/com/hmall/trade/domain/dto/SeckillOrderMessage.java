package com.hmall.trade.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 秒杀下单 MQ 消息体
 * <p>
 * 由 SeckillServiceImpl 在 Lua 预减成功后发送到 seckill.order.queue，
 * 由 SeckillOrderListener 消费执行 MySQL 行锁扣减 + 创建订单。
 */
@Data
@Accessors(chain = true)
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品关联ID（seckill_product_relation.id）
     */
    private Long relationId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 商品ID（item表）
     */
    private Long productId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 秒杀价（分）
     */
    private Integer seckillPrice;

    /**
     * 限购数量
     */
    private Integer limitNum;
}
