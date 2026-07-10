package com.hmall.cart.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 购物车同步 MQ 消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartSyncMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 商品 ID
     */
    private Long itemId;

    /**
     * 商品名称
     */
    private String name;

    /**
     * 商品规格
     */
    private String spec;

    /**
     * 价格（分）
     */
    private Integer price;

    /**
     * 商品图片
     */
    private String image;

    /**
     * 数量
     */
    private Integer num;

    /**
     * 版本号（时间戳）
     */
    private Long version;
}
