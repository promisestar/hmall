package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀商品关联管理 VO（管理后台列表/详情，含商品信息和实时库存）
 */
@Data
@Accessors(chain = true)
public class SeckillProductRelationAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long promotionId;

    private Long sessionId;

    private Long productId;

    /**
     * 商品名称（来自 item-service）
     */
    private String productName;

    /**
     * 商品图片（来自 item-service）
     */
    private String productImage;

    /**
     * 秒杀价（分）
     */
    private Integer seckillPrice;

    /**
     * 秒杀总库存
     */
    private Integer stock;

    /**
     * 每人限购数量
     */
    private Integer limitNum;

    /**
     * Redis 剩余库存（预热后有值，未预热时等于 stock）
     */
    private Integer remainingStock;

    /**
     * 已售数量
     */
    private Integer soldCount;

    /**
     * 是否已预热到 Redis
     */
    private Boolean preheated;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
