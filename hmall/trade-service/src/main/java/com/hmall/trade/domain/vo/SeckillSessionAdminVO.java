package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀场次管理 VO（管理后台列表/详情）
 */
@Data
@Accessors(chain = true)
public class SeckillSessionAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long promotionId;

    /**
     * 活动标题（关联查询）
     */
    private String promotionTitle;

    private String name;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /**
     * 状态: 0未开始 1进行中 2已结束
     */
    private Integer status;

    /**
     * 商品数量
     */
    private Integer productCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
