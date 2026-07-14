package com.hmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 秒杀活动表
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("seckill_promotion")
public class SeckillPromotion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 活动标题
     */
    private String title;

    /**
     * 活动开始日期
     */
    private LocalDate startDate;

    /**
     * 活动结束日期
     */
    private LocalDate endDate;

    /**
     * 状态: 0未开始 1进行中 2已结束
     */
    private Integer status;

    private java.time.LocalDateTime createTime;

    private java.time.LocalDateTime updateTime;
}
