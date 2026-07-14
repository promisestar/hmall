package com.hmall.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmall.trade.domain.po.SeckillDailyStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 每日库存快照 Mapper
 * <p>
 * 包含 FOR UPDATE 行锁查询和原子扣减/回补 SQL，
 * 是三层防超卖架构的第三层（MySQL 行锁兜底）。
 */
public interface SeckillDailyStockMapper extends BaseMapper<SeckillDailyStock> {

    /**
     * 行锁查询：SELECT ... FOR UPDATE
     * 必须在事务内调用，锁定 (relation_id, batch_date) 行直到事务提交。
     *
     * @param relationId 商品关联ID
     * @param batchDate  批次日期
     * @return 锁定的库存记录
     */
    @Select("SELECT * FROM seckill_daily_stock WHERE relation_id = #{relationId} AND batch_date = #{batchDate} FOR UPDATE")
    SeckillDailyStock selectForUpdate(@Param("relationId") Long relationId,
                                      @Param("batchDate") LocalDate batchDate);

    /**
     * 原子扣减库存：WHERE stock >= quantity 保证不超卖
     *
     * @param relationId 商品关联ID
     * @param batchDate  批次日期
     * @param quantity   扣减数量
     * @return 影响行数：1=成功，0=库存不足
     */
    @Update("UPDATE seckill_daily_stock SET stock = stock - #{quantity}, sold = sold + #{quantity} " +
            "WHERE relation_id = #{relationId} AND batch_date = #{batchDate} AND stock >= #{quantity}")
    int deductStock(@Param("relationId") Long relationId,
                    @Param("batchDate") LocalDate batchDate,
                    @Param("quantity") int quantity);

    /**
     * 回补库存（超时关单时调用）
     *
     * @param relationId 商品关联ID
     * @param batchDate  批次日期
     * @param quantity   回补数量
     * @return 影响行数
     */
    @Update("UPDATE seckill_daily_stock SET stock = stock + #{quantity}, sold = sold - #{quantity} " +
            "WHERE relation_id = #{relationId} AND batch_date = #{batchDate}")
    int recoverStock(@Param("relationId") Long relationId,
                     @Param("batchDate") LocalDate batchDate,
                     @Param("quantity") int quantity);
}
