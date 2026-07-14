package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀商品详情 VO（含商品信息、秒杀价、剩余库存、限购数）
 */
@Data
@Accessors(chain = true)
public class SeckillProductVO implements Serializable {

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
     * 商品名称
     */
    private String name;

    /**
     * 商品图片
     */
    private String image;

    /**
     * 商品规格
     */
    private String spec;

    /**
     * 原价（分）
     */
    private Integer originalPrice;

    /**
     * 秒杀价（分）
     */
    private Integer seckillPrice;

    /**
     * 秒杀总库存
     */
    private Integer totalStock;

    /**
     * 剩余库存（Redis 实时值）
     */
    private Integer remainingStock;

    /**
     * 已售数量
     */
    private Integer soldCount;

    /**
     * 每人限购数量
     */
    private Integer limitNum;

    /**
     * 活动状态: 0未开始 1进行中 2已结束
     */
    private Integer status;

    /**
     * 场次开始时间（倒计时用）
     */
    private LocalDateTime startTime;

    /**
     * 场次结束时间（倒计时用）
     */
    private LocalDateTime endTime;
}
