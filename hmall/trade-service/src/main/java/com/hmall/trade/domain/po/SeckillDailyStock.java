package com.hmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日库存快照表（底层防超卖行锁目标）
 * UNIQUE(relation_id, batch_date)
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("seckill_daily_stock")
public class SeckillDailyStock implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联 seckill_product_relation.id
     */
    private Long relationId;

    /**
     * 批次日期
     */
    private LocalDate batchDate;

    /**
     * 当日剩余库存
     */
    private Integer stock;

    /**
     * 已售数量
     */
    private Integer sold;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
