package com.hmall.trade.service;

import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.trade.domain.dto.SeckillProductRelationDTO;
import com.hmall.trade.domain.dto.SeckillPromotionDTO;
import com.hmall.trade.domain.dto.SeckillSessionDTO;
import com.hmall.trade.domain.vo.*;

import java.util.List;

/**
 * 秒杀服务接口
 */
public interface SeckillService {

    // ==================== C 端接口 ====================

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

    // ==================== 管理后台 - 活动管理 ====================

    /**
     * 分页查询秒杀活动列表
     */
    PageDTO<SeckillPromotionAdminVO> queryPromotionPage(PageQuery pageQuery, String title, Integer status);

    /**
     * 查询秒杀活动详情
     */
    SeckillPromotionAdminVO getPromotionDetail(Long id);

    /**
     * 创建秒杀活动
     */
    Long createPromotion(SeckillPromotionDTO dto);

    /**
     * 修改秒杀活动
     */
    void updatePromotion(SeckillPromotionDTO dto);

    /**
     * 删除秒杀活动（级联删除场次和商品关联）
     */
    void deletePromotion(Long id);

    // ==================== 管理后台 - 场次管理 ====================

    /**
     * 分页查询秒杀场次列表
     */
    PageDTO<SeckillSessionAdminVO> querySessionPage(PageQuery pageQuery, Long promotionId);

    /**
     * 查询秒杀场次详情
     */
    SeckillSessionAdminVO getSessionDetail(Long id);

    /**
     * 创建秒杀场次
     */
    Long createSession(SeckillSessionDTO dto);

    /**
     * 修改秒杀场次
     */
    void updateSession(SeckillSessionDTO dto);

    /**
     * 删除秒杀场次（级联删除商品关联）
     */
    void deleteSession(Long id);

    // ==================== 管理后台 - 商品关联管理 ====================

    /**
     * 分页查询秒杀商品关联列表
     */
    PageDTO<SeckillProductRelationAdminVO> queryRelationPage(PageQuery pageQuery, Long sessionId, Long promotionId);

    /**
     * 查询秒杀商品关联详情
     */
    SeckillProductRelationAdminVO getRelationDetail(Long id);

    /**
     * 创建秒杀商品关联
     */
    Long createRelation(SeckillProductRelationDTO dto);

    /**
     * 修改秒杀商品关联
     */
    void updateRelation(SeckillProductRelationDTO dto);

    /**
     * 删除秒杀商品关联
     */
    void deleteRelation(Long id);

    /**
     * 手动预热秒杀库存到 Redis
     */
    void manualPreheat(Long relationId);

    // ==================== 管理后台 - 秒杀订单管理 ====================

    /**
     * 分页查询秒杀订单
     */
    PageDTO<SeckillOrderAdminVO> querySeckillOrderPage(PageQuery pageQuery, Integer status, Long relationId, Long userId);

    // ==================== 管理后台 - 库存查询 ====================

    /**
     * 查询商品关联的每日库存快照列表
     */
    List<SeckillStockAdminVO> queryStockStatus(Long relationId);
}
