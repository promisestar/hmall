package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 秒杀活动管理 VO（管理后台列表/详情）
 */
@Data
@Accessors(chain = true)
public class SeckillPromotionAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    private LocalDate startDate;

    private LocalDate endDate;

    /**
     * 状态: 0未开始 1进行中 2已结束
     */
    private Integer status;

    /**
     * 场次数量
     */
    private Integer sessionCount;

    /**
     * 商品数量
     */
    private Integer productCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
