package com.hmall.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.RedisLockUtil;
import com.hmall.common.utils.UserContext;
import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.dto.SeckillOrderMessage;
import com.hmall.trade.domain.po.SeckillDailyStock;
import com.hmall.trade.domain.po.SeckillProductRelation;
import com.hmall.trade.domain.po.SeckillPromotion;
import com.hmall.trade.domain.po.SeckillSession;
import com.hmall.trade.domain.vo.SeckillActivityVO;
import com.hmall.trade.domain.vo.SeckillProductVO;
import com.hmall.trade.domain.vo.SeckillResultVO;
import com.hmall.trade.mapper.SeckillDailyStockMapper;
import com.hmall.trade.mapper.SeckillProductRelationMapper;
import com.hmall.trade.mapper.SeckillPromotionMapper;
import com.hmall.trade.mapper.SeckillSessionMapper;
import com.hmall.trade.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 秒杀服务实现
 * <p>
 * 三层防超卖架构的第二层：Redis Lua 原子预减（限购+库存合一）。
 * 预减成功后发送 MQ 消息，由 SeckillOrderListener 执行第三层 MySQL 行锁扣减。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillProductRelationMapper relationMapper;
    private final SeckillDailyStockMapper dailyStockMapper;
    private final SeckillPromotionMapper promotionMapper;
    private final SeckillSessionMapper sessionMapper;
    private final ItemClient itemClient;
    private final RedisService redisService;
    private final RedisLockUtil redisLockUtil;
    private final RabbitTemplate rabbitTemplate;

    // ==================== Redis Key 前缀 ====================

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String LIMIT_KEY_PREFIX = "seckill:limit:";
    private static final String LOCK_KEY_PREFIX = "seckill:lock:user:";
    private static final String RESULT_KEY_PREFIX = "seckill:result:";

    private static final long LOCK_EXPIRE_SECONDS = 5;

    // ==================== Lua 脚本 ====================

    private static final String SECKILL_DEDUCT_LUA =
            com.hmall.common.utils.LuaScriptLoader.load("lua/seckill_deduct.lua");

    // ==================== 预热 ====================

    @Override
    public void preheat(Long relationId) {
        SeckillProductRelation relation = relationMapper.selectById(relationId);
        if (relation == null) {
            throw new BizIllegalException("秒杀商品不存在, relationId=" + relationId);
        }

        // 1. 将库存写入 Redis（String 类型，无过期时间，活动结束后手动清除）
        String stockKey = STOCK_KEY_PREFIX + relationId;
        redisService.set(stockKey, relation.getStock());

        // 2. 初始化每日库存快照（如果当天不存在）
        LocalDate today = LocalDate.now();
        SeckillDailyStock existing = dailyStockMapper.selectOne(
                new LambdaQueryWrapper<SeckillDailyStock>()
                        .eq(SeckillDailyStock::getRelationId, relationId)
                        .eq(SeckillDailyStock::getBatchDate, today)
        );
        if (existing == null) {
            SeckillDailyStock dailyStock = new SeckillDailyStock()
                    .setRelationId(relationId)
                    .setBatchDate(today)
                    .setStock(relation.getStock())
                    .setSold(0);
            try {
                dailyStockMapper.insert(dailyStock);
            } catch (Exception e) {
                // 并发插入时 UNIQUE 约束冲突，忽略（另一个线程已插入）
                log.debug("每日库存快照已存在, relationId={}, date={}", relationId, today);
            }
        }

        log.info("秒杀库存预热完成, relationId={}, stock={}", relationId, relation.getStock());
    }

    // ==================== 秒杀下单 ====================

    @Override
    public SeckillResultVO doSeckill(Long relationId, Integer quantity) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new BizIllegalException("用户未登录");
        }
        if (quantity == null || quantity < 1) {
            quantity = 1;
        }

        // 1. 查询秒杀商品关联信息
        SeckillProductRelation relation = relationMapper.selectById(relationId);
        if (relation == null) {
            return SeckillResultVO.fail("秒杀商品不存在");
        }

        // 2. per-user 分布式锁（防重复提交）
        String lockKey = LOCK_KEY_PREFIX + userId;
        String lockValue = UUID.randomUUID().toString();
        boolean locked = redisLockUtil.tryLock(lockKey, lockValue, LOCK_EXPIRE_SECONDS);
        if (!locked) {
            return SeckillResultVO.fail("请勿重复提交");
        }

        try {
            // 3. Lua 原子预减（限购检查 + 库存扣减合一）
            String stockKey = STOCK_KEY_PREFIX + relationId;
            String limitKey = LIMIT_KEY_PREFIX + relationId;

            Long result = redisService.executeScript(
                    SECKILL_DEDUCT_LUA, Long.class,
                    Arrays.asList(stockKey, limitKey),
                    String.valueOf(userId),
                    String.valueOf(quantity),
                    String.valueOf(relation.getLimitNum())
            );

            if (result == null) {
                // Redis 异常被 RedisCacheAspect 捕获后返回 null
                log.warn("秒杀Lua预减返回null, userId={}, relationId={}", userId, relationId);
                return SeckillResultVO.fail("系统繁忙，请稍后重试");
            }

            switch (result.intValue()) {
                case 1:
                    // 预减成功 → 发送 MQ 异步下单
                    SeckillOrderMessage message = new SeckillOrderMessage()
                            .setRelationId(relationId)
                            .setUserId(userId)
                            .setProductId(relation.getProductId())
                            .setQuantity(quantity)
                            .setSeckillPrice(relation.getSeckillPrice())
                            .setLimitNum(relation.getLimitNum());

                    try {
                        rabbitTemplate.convertAndSend(
                                MQConstants.SECKILL_EXCHANGE_NAME,
                                MQConstants.SECKILL_ORDER_KEY,
                                message
                        );
                    } catch (Exception e) {
                        // MQ 发送失败 → 回补 Redis 库存和限购
                        log.error("秒杀MQ发送失败, userId={}, relationId={}", userId, relationId, e);
                        redisService.incrBy(stockKey, quantity);
                        redisService.hIncrBy(limitKey, String.valueOf(userId), -quantity);
                        return SeckillResultVO.fail("系统繁忙，请稍后重试");
                    }

                    log.info("秒杀下单 userId={}, relationId={}, quantity={}, result=排队中", userId, relationId, quantity);
                    return SeckillResultVO.pending();

                case 0:
                    log.info("秒杀下单 userId={}, relationId={}, result=已售罄", userId, relationId);
                    return SeckillResultVO.fail("已售罄");

                case -1:
                    log.warn("秒杀下单 userId={}, relationId={}, result=未初始化", userId, relationId);
                    return SeckillResultVO.fail("活动未开始");

                case -2:
                    log.info("秒杀下单 userId={}, relationId={}, result=超限购", userId, relationId);
                    return SeckillResultVO.fail("超过限购数量");

                default:
                    log.error("秒杀Lua预减未知返回值 result={}, userId={}, relationId={}", result, userId, relationId);
                    return SeckillResultVO.fail("系统异常");
            }
        } finally {
            redisLockUtil.releaseLock(lockKey, lockValue);
        }
    }

    // ==================== 查询活动列表 ====================

    @Override
    public List<SeckillActivityVO> queryActivities() {
        // 1. 查询所有活动（按创建时间倒序）
        List<SeckillPromotion> promotions = promotionMapper.selectList(
                new LambdaQueryWrapper<SeckillPromotion>()
                        .orderByDesc(SeckillPromotion::getCreateTime)
        );

        List<SeckillActivityVO> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (SeckillPromotion promotion : promotions) {
            SeckillActivityVO activityVO = new SeckillActivityVO()
                    .setId(promotion.getId())
                    .setTitle(promotion.getTitle())
                    .setStatus(promotion.getStatus());

            // 2. 查询该活动下的场次
            List<SeckillSession> sessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<SeckillSession>()
                            .eq(SeckillSession::getPromotionId, promotion.getId())
                            .orderByAsc(SeckillSession::getStartTime)
            );

            List<SeckillActivityVO.SessionVO> sessionVOs = new ArrayList<>();
            for (SeckillSession session : sessions) {
                SeckillActivityVO.SessionVO sessionVO = new SeckillActivityVO.SessionVO()
                        .setId(session.getId())
                        .setName(session.getName())
                        .setStartTime(session.getStartTime())
                        .setEndTime(session.getEndTime())
                        .setStatus(getSessionStatus(session, now));

                // 3. 查询该场次下的商品
                List<SeckillProductRelation> relations = relationMapper.selectList(
                        new LambdaQueryWrapper<SeckillProductRelation>()
                                .eq(SeckillProductRelation::getSessionId, session.getId())
                );

                List<SeckillProductVO> productVOs = new ArrayList<>();
                for (SeckillProductRelation relation : relations) {
                    SeckillProductVO productVO = buildProductVO(relation);
                    productVO.setStatus(sessionVO.getStatus());
                    productVO.setStartTime(session.getStartTime());
                    productVO.setEndTime(session.getEndTime());
                    productVOs.add(productVO);
                }
                sessionVO.setProducts(productVOs);
                sessionVOs.add(sessionVO);
            }
            activityVO.setSessions(sessionVOs);
            result.add(activityVO);
        }

        return result;
    }

    // ==================== 查询商品详情 ====================

    @Override
    public SeckillProductVO queryProduct(Long relationId) {
        SeckillProductRelation relation = relationMapper.selectById(relationId);
        if (relation == null) {
            throw new BizIllegalException("秒杀商品不存在");
        }

        SeckillProductVO vo = buildProductVO(relation);

        // 查询场次状态
        SeckillSession session = sessionMapper.selectById(relation.getSessionId());
        if (session != null) {
            vo.setStatus(getSessionStatus(session, LocalDateTime.now()));
            vo.setStartTime(session.getStartTime());
            vo.setEndTime(session.getEndTime());
        }

        return vo;
    }

    // ==================== 轮询订单结果 ====================

    @Override
    public SeckillResultVO getOrderResult(Long relationId) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new BizIllegalException("用户未登录");
        }

        String resultKey = RESULT_KEY_PREFIX + userId + ":" + relationId;
        Object resultObj = redisService.get(resultKey);

        if (resultObj == null) {
            // Key 不存在 = 仍在排队中
            return SeckillResultVO.pending();
        }

        String resultStr = resultObj.toString();
        if ("0".equals(resultStr)) {
            // MQ 消费者扣减失败（MySQL 库存不足）
            return SeckillResultVO.fail("已售罄");
        }

        try {
            Long orderId = Long.parseLong(resultStr);
            return SeckillResultVO.success(orderId);
        } catch (NumberFormatException e) {
            log.warn("秒杀结果key格式异常, key={}, value={}", resultKey, resultStr);
            return SeckillResultVO.pending();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建 SeckillProductVO（含商品信息和 Redis 实时库存）
     */
    private SeckillProductVO buildProductVO(SeckillProductRelation relation) {
        SeckillProductVO vo = new SeckillProductVO()
                .setRelationId(relation.getId())
                .setProductId(relation.getProductId())
                .setSeckillPrice(relation.getSeckillPrice())
                .setTotalStock(relation.getStock())
                .setLimitNum(relation.getLimitNum());

        // 查询商品信息（通过 Feign 调用 item-service）
        try {
            ItemDTO item = itemClient.queryItemById(relation.getProductId());
            if (item != null) {
                vo.setName(item.getName());
                vo.setImage(item.getImage());
                vo.setSpec(item.getSpec());
                vo.setOriginalPrice(item.getPrice());
            }
        } catch (Exception e) {
            log.warn("查询商品信息失败, productId={}", relation.getProductId(), e);
        }

        // 从 Redis 读取实时剩余库存
        String stockKey = STOCK_KEY_PREFIX + relation.getId();
        Object stockObj = redisService.get(stockKey);
        if (stockObj != null) {
            try {
                int remaining = Integer.parseInt(stockObj.toString());
                vo.setRemainingStock(remaining);
                vo.setSoldCount(relation.getStock() - remaining);
            } catch (NumberFormatException e) {
                vo.setRemainingStock(relation.getStock());
                vo.setSoldCount(0);
            }
        } else {
            // Redis 未预热，显示总库存
            vo.setRemainingStock(relation.getStock());
            vo.setSoldCount(0);
        }

        return vo;
    }

    /**
     * 计算场次状态：0未开始 1进行中 2已结束
     */
    private int getSessionStatus(SeckillSession session, LocalDateTime now) {
        if (now.isBefore(session.getStartTime())) {
            return 0; // 未开始
        } else if (now.isAfter(session.getEndTime())) {
            return 2; // 已结束
        } else {
            return 1; // 进行中
        }
    }
}
