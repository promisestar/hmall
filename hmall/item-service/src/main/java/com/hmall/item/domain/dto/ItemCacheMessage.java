package com.hmall.item.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 商品缓存失效 MQ 消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemCacheMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 ID
     */
    private Long itemId;

    /**
     * 操作时间戳
     */
    private Long timestamp;
}
