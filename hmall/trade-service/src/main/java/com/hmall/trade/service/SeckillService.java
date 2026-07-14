package com.hmall.trade.service;

import com.hmall.trade.domain.vo.SeckillActivityVO;
import com.hmall.trade.domain.vo.SeckillProductVO;
import com.hmall.trade.domain.vo.SeckillResultVO;

import java.util.List;

/**
 * 秒杀服务接口
 */
public interface SeckillService {

    /**
     * 预热秒杀库存到 Redis
     * <p>
     * 将 seckill_product_relation 的库存写入 Redis，
     * 并初始化 seckill_daily_stock 每日库存快照。
     *
     * @param relationId 商品关联ID
     */
    void preheat(Long relationId);

    /**
     * 执行秒杀下单
     * <p>
     * 流程：分布式锁 → Lua 原子预减（限购+库存）→ 发送 MQ 异步下单
     *
     * @param relationId 商品关联ID
     * @param quantity   购买数量
     * @return 秒杀结果（pending=排队中, failed=失败）
     */
    SeckillResultVO doSeckill(Long relationId, Integer quantity);

    /**
     * 查询秒杀活动列表（含场次和商品）
     *
     * @return 活动列表
     */
    List<SeckillActivityVO> queryActivities();

    /**
     * 查询秒杀商品详情
     *
     * @param relationId 商品关联ID
     * @return 商品详情
     */
    SeckillProductVO queryProduct(Long relationId);

    /**
     * 轮询秒杀订单结果
     * <p>
     * 从 Redis 读取 MQ 消费者写入的结果 key。
     *
     * @param relationId 商品关联ID
     * @return 订单结果（success=成功含orderId, pending=排队中, failed=失败）
     */
    SeckillResultVO getOrderResult(Long relationId);
}
