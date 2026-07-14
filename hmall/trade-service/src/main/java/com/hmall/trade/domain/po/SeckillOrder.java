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
 * 秒杀订单关联表（追踪秒杀订单，用于超时回补）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("seckill_order")
public class SeckillOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 订单ID（order表）
     */
    private Long orderId;

    /**
     * 关联 seckill_product_relation.id
     */
    private Long relationId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 状态: 1待支付 2已支付 3已关闭
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
