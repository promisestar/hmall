package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀订单管理 VO（管理后台列表）
 */
@Data
@Accessors(chain = true)
public class SeckillOrderAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long orderId;

    private Long relationId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 商品名称（来自 item-service）
     */
    private String productName;

    private Long userId;

    private Integer quantity;

    /**
     * 秒杀价（分）
     */
    private Integer seckillPrice;

    /**
     * 状态: 1待支付 2已支付 3已关闭
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
