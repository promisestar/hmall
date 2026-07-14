package com.hmall.api.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 秒杀商品 DTO（跨服务传递用）
 */
@Data
@Accessors(chain = true)
public class SeckillProductDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品关联ID（seckill_product_relation.id）
     */
    private Long relationId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 秒杀价（分）
     */
    private Integer seckillPrice;

    /**
     * 秒杀库存
     */
    private Integer stock;

    /**
     * 限购数量
     */
    private Integer limitNum;

    /**
     * 商品信息（用于前端展示）
     */
    private ItemDTO itemInfo;
}
