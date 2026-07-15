package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 秒杀每日库存快照管理 VO（管理后台库存查询）
 */
@Data
@Accessors(chain = true)
public class SeckillStockAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long relationId;

    private LocalDate batchDate;

    /**
     * 当日总库存
     */
    private Integer stock;

    /**
     * 已售数量
     */
    private Integer sold;

    /**
     * 剩余库存（stock - sold）
     */
    private Integer remaining;
}
