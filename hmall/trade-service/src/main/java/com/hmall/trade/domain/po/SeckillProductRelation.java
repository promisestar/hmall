package com.hmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀活动-商品关联表（含秒杀价+库存+限购数）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("seckill_product_relation")
public class SeckillProductRelation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 活动ID
     */
    private Long promotionId;

    /**
     * 场次ID
     */
    private Long sessionId;

    /**
     * 商品ID（item表）
     */
    private Long productId;

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

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
