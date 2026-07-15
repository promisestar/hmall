package com.hmall.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.RedisLockUtil;
import com.hmall.common.utils.UserContext;
import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.dto.SeckillOrderMessage;
import com.hmall.trade.domain.dto.SeckillProductRelationDTO;
import com.hmall.trade.domain.dto.SeckillPromotionDTO;
import com.hmall.trade.domain.dto.SeckillSessionDTO;
import com.hmall.trade.domain.po.SeckillDailyStock;
import com.hmall.trade.domain.po.SeckillOrder;
import com.hmall.trade.domain.po.SeckillProductRelation;
import com.hmall.trade.domain.po.SeckillPromotion;
import com.hmall.trade.domain.po.SeckillSession;
import com.hmall.trade.domain.vo.SeckillActivityVO;
import com.hmall.trade.domain.vo.SeckillOrderAdminVO;
import com.hmall.trade.domain.vo.SeckillProductRelationAdminVO;
import com.hmall.trade.domain.vo.SeckillProductVO;
import com.hmall.trade.domain.vo.SeckillPromotionAdminVO;
import com.hmall.trade.domain.vo.SeckillResultVO;
import com.hmall.trade.domain.vo.SeckillSessionAdminVO;
import com.hmall.trade.domain.vo.SeckillStockAdminVO;
import com.hmall.trade.mapper.SeckillDailyStockMapper;
import com.hmall.trade.mapper.SeckillOrderMapper;
import com.hmall.trade.mapper.SeckillProductRelationMapper;
import com.hmall.trade.mapper.SeckillPromotionMapper;
import com.hmall.trade.mapper.SeckillSessionMapper;
import com.hmall.trade.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final SeckillOrderMapper orderMapper;
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

    /**
     * 计算活动状态：0未开始 1进行中 2已结束
     */
    private int computePromotionStatus(LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now();
        if (today.isBefore(startDate)) {
            return 0; // 未开始
        } else if (today.isAfter(endDate)) {
            return 2; // 已结束
        } else {
            return 1; // 进行中
        }
    }

    // ==================== 管理后台 - 活动管理 ====================

    @Override
    public PageDTO<SeckillPromotionAdminVO> queryPromotionPage(PageQuery pageQuery, String title, Integer status) {
        LambdaQueryWrapper<SeckillPromotion> wrapper = new LambdaQueryWrapper<>();
        if (title != null && !title.isEmpty()) {
            wrapper.like(SeckillPromotion::getTitle, title);
        }
        if (status != null) {
            wrapper.eq(SeckillPromotion::getStatus, status);
        }

        Page<SeckillPromotion> page = promotionMapper.selectPage(
                pageQuery.toMpPageDefaultSortByCreateTimeDesc(), wrapper);

        List<SeckillPromotionAdminVO> list = page.getRecords().stream().map(p -> {
            SeckillPromotionAdminVO vo = new SeckillPromotionAdminVO()
                    .setId(p.getId())
                    .setTitle(p.getTitle())
                    .setStartDate(p.getStartDate())
                    .setEndDate(p.getEndDate())
                    .setStatus(p.getStatus())
                    .setCreateTime(p.getCreateTime())
                    .setUpdateTime(p.getUpdateTime());
            long sessionCount = sessionMapper.selectCount(
                    new LambdaQueryWrapper<SeckillSession>().eq(SeckillSession::getPromotionId, p.getId()));
            vo.setSessionCount((int) sessionCount);
            long productCount = relationMapper.selectCount(
                    new LambdaQueryWrapper<SeckillProductRelation>().eq(SeckillProductRelation::getPromotionId, p.getId()));
            vo.setProductCount((int) productCount);
            return vo;
        }).collect(Collectors.toList());

        return PageDTO.of(page, list);
    }

    @Override
    public SeckillPromotionAdminVO getPromotionDetail(Long id) {
        SeckillPromotion p = promotionMapper.selectById(id);
        if (p == null) {
            throw new BizIllegalException("秒杀活动不存在");
        }
        SeckillPromotionAdminVO vo = new SeckillPromotionAdminVO()
                .setId(p.getId())
                .setTitle(p.getTitle())
                .setStartDate(p.getStartDate())
                .setEndDate(p.getEndDate())
                .setStatus(p.getStatus())
                .setCreateTime(p.getCreateTime())
                .setUpdateTime(p.getUpdateTime());
        long sessionCount = sessionMapper.selectCount(
                new LambdaQueryWrapper<SeckillSession>().eq(SeckillSession::getPromotionId, id));
        vo.setSessionCount((int) sessionCount);
        long productCount = relationMapper.selectCount(
                new LambdaQueryWrapper<SeckillProductRelation>().eq(SeckillProductRelation::getPromotionId, id));
        vo.setProductCount((int) productCount);
        return vo;
    }

    @Override
    public Long createPromotion(SeckillPromotionDTO dto) {
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BizIllegalException("开始日期不能晚于结束日期");
        }
        SeckillPromotion promotion = new SeckillPromotion()
                .setTitle(dto.getTitle())
                .setStartDate(dto.getStartDate())
                .setEndDate(dto.getEndDate())
                .setStatus(computePromotionStatus(dto.getStartDate(), dto.getEndDate()));
        promotionMapper.insert(promotion);
        log.info("创建秒杀活动 id={}, title={}", promotion.getId(), promotion.getTitle());
        return promotion.getId();
    }

    @Override
    public void updatePromotion(SeckillPromotionDTO dto) {
        if (dto.getId() == null) {
            throw new BizIllegalException("活动ID不能为空");
        }
        SeckillPromotion existing = promotionMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizIllegalException("秒杀活动不存在");
        }
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BizIllegalException("开始日期不能晚于结束日期");
        }
        // 进行中的活动不允许修改日期
        if (existing.getStatus() == 1) {
            if (!existing.getStartDate().equals(dto.getStartDate()) || !existing.getEndDate().equals(dto.getEndDate())) {
                throw new BizIllegalException("进行中的活动不允许修改日期");
            }
        }
        SeckillPromotion promotion = new SeckillPromotion()
                .setId(dto.getId())
                .setTitle(dto.getTitle())
                .setStartDate(dto.getStartDate())
                .setEndDate(dto.getEndDate())
                .setStatus(computePromotionStatus(dto.getStartDate(), dto.getEndDate()));
        promotionMapper.updateById(promotion);
        log.info("修改秒杀活动 id={}", dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePromotion(Long id) {
        SeckillPromotion promotion = promotionMapper.selectById(id);
        if (promotion == null) {
            throw new BizIllegalException("秒杀活动不存在");
        }
        if (promotion.getStatus() == 1) {
            throw new BizIllegalException("进行中的活动不允许删除");
        }
        // 级联删除场次（含场次下的商品关联和库存快照）
        List<SeckillSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<SeckillSession>().eq(SeckillSession::getPromotionId, id));
        for (SeckillSession session : sessions) {
            deleteSessionCascade(session.getId());
        }
        promotionMapper.deleteById(id);
        log.info("删除秒杀活动 id={}, title={}", id, promotion.getTitle());
    }

    // ==================== 管理后台 - 场次管理 ====================

    @Override
    public PageDTO<SeckillSessionAdminVO> querySessionPage(PageQuery pageQuery, Long promotionId) {
        LambdaQueryWrapper<SeckillSession> wrapper = new LambdaQueryWrapper<>();
        if (promotionId != null) {
            wrapper.eq(SeckillSession::getPromotionId, promotionId);
        }
        Page<SeckillSession> page = sessionMapper.selectPage(
                pageQuery.toMpPageDefaultSortByCreateTimeDesc(), wrapper);

        // 批量查询活动标题
        List<Long> promotionIds = page.getRecords().stream()
                .map(SeckillSession::getPromotionId).distinct().collect(Collectors.toList());
        Map<Long, String> titleMap = loadPromotionTitleMap(promotionIds);

        List<SeckillSessionAdminVO> list = page.getRecords().stream().map(s -> {
            SeckillSessionAdminVO vo = new SeckillSessionAdminVO()
                    .setId(s.getId())
                    .setPromotionId(s.getPromotionId())
                    .setPromotionTitle(titleMap.get(s.getPromotionId()))
                    .setName(s.getName())
                    .setStartTime(s.getStartTime())
                    .setEndTime(s.getEndTime())
                    .setStatus(getSessionStatus(s, LocalDateTime.now()))
                    .setCreateTime(s.getCreateTime())
                    .setUpdateTime(s.getUpdateTime());
            long productCount = relationMapper.selectCount(
                    new LambdaQueryWrapper<SeckillProductRelation>().eq(SeckillProductRelation::getSessionId, s.getId()));
            vo.setProductCount((int) productCount);
            return vo;
        }).collect(Collectors.toList());

        return PageDTO.of(page, list);
    }

    @Override
    public SeckillSessionAdminVO getSessionDetail(Long id) {
        SeckillSession s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizIllegalException("秒杀场次不存在");
        }
        SeckillPromotion promotion = promotionMapper.selectById(s.getPromotionId());
        SeckillSessionAdminVO vo = new SeckillSessionAdminVO()
                .setId(s.getId())
                .setPromotionId(s.getPromotionId())
                .setPromotionTitle(promotion != null ? promotion.getTitle() : null)
                .setName(s.getName())
                .setStartTime(s.getStartTime())
                .setEndTime(s.getEndTime())
                .setStatus(getSessionStatus(s, LocalDateTime.now()))
                .setCreateTime(s.getCreateTime())
                .setUpdateTime(s.getUpdateTime());
        long productCount = relationMapper.selectCount(
                new LambdaQueryWrapper<SeckillProductRelation>().eq(SeckillProductRelation::getSessionId, id));
        vo.setProductCount((int) productCount);
        return vo;
    }

    @Override
    public Long createSession(SeckillSessionDTO dto) {
        SeckillPromotion promotion = promotionMapper.selectById(dto.getPromotionId());
        if (promotion == null) {
            throw new BizIllegalException("秒杀活动不存在");
        }
        if (dto.getStartTime().isAfter(dto.getEndTime())) {
            throw new BizIllegalException("开始时间不能晚于结束时间");
        }
        SeckillSession session = new SeckillSession()
                .setPromotionId(dto.getPromotionId())
                .setName(dto.getName())
                .setStartTime(dto.getStartTime())
                .setEndTime(dto.getEndTime());
        sessionMapper.insert(session);
        log.info("创建秒杀场次 id={}, promotionId={}", session.getId(), dto.getPromotionId());
        return session.getId();
    }

    @Override
    public void updateSession(SeckillSessionDTO dto) {
        if (dto.getId() == null) {
            throw new BizIllegalException("场次ID不能为空");
        }
        SeckillSession existing = sessionMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizIllegalException("秒杀场次不存在");
        }
        if (dto.getStartTime().isAfter(dto.getEndTime())) {
            throw new BizIllegalException("开始时间不能晚于结束时间");
        }
        // 进行中的场次不允许修改时间
        int currentStatus = getSessionStatus(existing, LocalDateTime.now());
        if (currentStatus == 1) {
            if (!existing.getStartTime().equals(dto.getStartTime()) || !existing.getEndTime().equals(dto.getEndTime())) {
                throw new BizIllegalException("进行中的场次不允许修改时间");
            }
        }
        SeckillSession session = new SeckillSession()
                .setId(dto.getId())
                .setPromotionId(dto.getPromotionId())
                .setName(dto.getName())
                .setStartTime(dto.getStartTime())
                .setEndTime(dto.getEndTime());
        sessionMapper.updateById(session);
        log.info("修改秒杀场次 id={}", dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long id) {
        SeckillSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new BizIllegalException("秒杀场次不存在");
        }
        int currentStatus = getSessionStatus(session, LocalDateTime.now());
        if (currentStatus == 1) {
            throw new BizIllegalException("进行中的场次不允许删除");
        }
        deleteSessionCascade(id);
        log.info("删除秒杀场次 id={}", id);
    }

    // ==================== 管理后台 - 商品关联管理 ====================

    @Override
    public PageDTO<SeckillProductRelationAdminVO> queryRelationPage(PageQuery pageQuery, Long sessionId, Long promotionId) {
        LambdaQueryWrapper<SeckillProductRelation> wrapper = new LambdaQueryWrapper<>();
        if (sessionId != null) {
            wrapper.eq(SeckillProductRelation::getSessionId, sessionId);
        }
        if (promotionId != null) {
            wrapper.eq(SeckillProductRelation::getPromotionId, promotionId);
        }
        Page<SeckillProductRelation> page = relationMapper.selectPage(
                pageQuery.toMpPageDefaultSortByCreateTimeDesc(), wrapper);

        // 批量查询商品信息
        List<Long> productIds = page.getRecords().stream()
                .map(SeckillProductRelation::getProductId).distinct().collect(Collectors.toList());
        Map<Long, ItemDTO> itemMap = loadItemMap(productIds);

        List<SeckillProductRelationAdminVO> list = page.getRecords().stream()
                .map(r -> buildRelationAdminVO(r, itemMap))
                .collect(Collectors.toList());

        return PageDTO.of(page, list);
    }

    @Override
    public SeckillProductRelationAdminVO getRelationDetail(Long id) {
        SeckillProductRelation r = relationMapper.selectById(id);
        if (r == null) {
            throw new BizIllegalException("秒杀商品关联不存在");
        }
        Map<Long, ItemDTO> itemMap = loadItemMap(Collections.singletonList(r.getProductId()));
        return buildRelationAdminVO(r, itemMap);
    }

    @Override
    public Long createRelation(SeckillProductRelationDTO dto) {
        SeckillPromotion promotion = promotionMapper.selectById(dto.getPromotionId());
        if (promotion == null) {
            throw new BizIllegalException("秒杀活动不存在");
        }
        SeckillSession session = sessionMapper.selectById(dto.getSessionId());
        if (session == null) {
            throw new BizIllegalException("秒杀场次不存在");
        }
        if (!session.getPromotionId().equals(dto.getPromotionId())) {
            throw new BizIllegalException("场次不属于该活动");
        }
        SeckillProductRelation relation = new SeckillProductRelation()
                .setPromotionId(dto.getPromotionId())
                .setSessionId(dto.getSessionId())
                .setProductId(dto.getProductId())
                .setSeckillPrice(dto.getSeckillPrice())
                .setStock(dto.getStock())
                .setLimitNum(dto.getLimitNum());
        relationMapper.insert(relation);
        log.info("创建秒杀商品关联 id={}, productId={}", relation.getId(), dto.getProductId());
        return relation.getId();
    }

    @Override
    public void updateRelation(SeckillProductRelationDTO dto) {
        if (dto.getId() == null) {
            throw new BizIllegalException("商品关联ID不能为空");
        }
        SeckillProductRelation existing = relationMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizIllegalException("秒杀商品关联不存在");
        }
        // 进行中的商品关联不允许修改库存和秒杀价
        SeckillSession session = sessionMapper.selectById(existing.getSessionId());
        if (session != null) {
            int sessionStatus = getSessionStatus(session, LocalDateTime.now());
            if (sessionStatus == 1) {
                if (!existing.getStock().equals(dto.getStock()) || !existing.getSeckillPrice().equals(dto.getSeckillPrice())) {
                    throw new BizIllegalException("进行中的秒杀商品不允许修改库存和秒杀价");
                }
            }
        }
        SeckillProductRelation relation = new SeckillProductRelation()
                .setId(dto.getId())
                .setPromotionId(dto.getPromotionId())
                .setSessionId(dto.getSessionId())
                .setProductId(dto.getProductId())
                .setSeckillPrice(dto.getSeckillPrice())
                .setStock(dto.getStock())
                .setLimitNum(dto.getLimitNum());
        relationMapper.updateById(relation);
        log.info("修改秒杀商品关联 id={}", dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelation(Long id) {
        SeckillProductRelation relation = relationMapper.selectById(id);
        if (relation == null) {
            throw new BizIllegalException("秒杀商品关联不存在");
        }
        clearRelationCache(id);
        relationMapper.deleteById(id);
        // 删除每日库存快照
        dailyStockMapper.delete(new LambdaQueryWrapper<SeckillDailyStock>()
                .eq(SeckillDailyStock::getRelationId, id));
        log.info("删除秒杀商品关联 id={}", id);
    }

    @Override
    public void manualPreheat(Long relationId) {
        preheat(relationId);
        log.info("管理后台手动预热秒杀库存, relationId={}", relationId);
    }

    // ==================== 管理后台 - 秒杀订单管理 ====================

    @Override
    public PageDTO<SeckillOrderAdminVO> querySeckillOrderPage(PageQuery pageQuery, Integer status, Long relationId, Long userId) {
        LambdaQueryWrapper<SeckillOrder> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(SeckillOrder::getStatus, status);
        }
        if (relationId != null) {
            wrapper.eq(SeckillOrder::getRelationId, relationId);
        }
        if (userId != null) {
            wrapper.eq(SeckillOrder::getUserId, userId);
        }

        Page<SeckillOrder> page = orderMapper.selectPage(
                pageQuery.toMpPageDefaultSortByCreateTimeDesc(), wrapper);

        // 批量查询商品关联信息（获取 productId 和 seckillPrice）
        List<Long> relationIds = page.getRecords().stream()
                .map(SeckillOrder::getRelationId).distinct().collect(Collectors.toList());
        Map<Long, SeckillProductRelation> relationMap = loadRelationMap(relationIds);

        // 批量查询商品信息
        List<Long> productIds = relationMap.values().stream()
                .map(SeckillProductRelation::getProductId).distinct().collect(Collectors.toList());
        Map<Long, ItemDTO> itemMap = loadItemMap(productIds);

        List<SeckillOrderAdminVO> list = page.getRecords().stream().map(o -> {
            SeckillOrderAdminVO vo = new SeckillOrderAdminVO()
                    .setId(o.getId())
                    .setOrderId(o.getOrderId())
                    .setRelationId(o.getRelationId())
                    .setUserId(o.getUserId())
                    .setQuantity(o.getQuantity())
                    .setStatus(o.getStatus())
                    .setCreateTime(o.getCreateTime())
                    .setUpdateTime(o.getUpdateTime());

            SeckillProductRelation relation = relationMap.get(o.getRelationId());
            if (relation != null) {
                vo.setProductId(relation.getProductId());
                vo.setSeckillPrice(relation.getSeckillPrice());
                ItemDTO item = itemMap.get(relation.getProductId());
                if (item != null) {
                    vo.setProductName(item.getName());
                }
            }
            return vo;
        }).collect(Collectors.toList());

        return PageDTO.of(page, list);
    }

    // ==================== 管理后台 - 库存查询 ====================

    @Override
    public List<SeckillStockAdminVO> queryStockStatus(Long relationId) {
        List<SeckillDailyStock> stocks = dailyStockMapper.selectList(
                new LambdaQueryWrapper<SeckillDailyStock>()
                        .eq(SeckillDailyStock::getRelationId, relationId)
                        .orderByDesc(SeckillDailyStock::getBatchDate));

        return stocks.stream().map(s -> new SeckillStockAdminVO()
                .setId(s.getId())
                .setRelationId(s.getRelationId())
                .setBatchDate(s.getBatchDate())
                .setStock(s.getStock())
                .setSold(s.getSold())
                .setRemaining(s.getStock() - s.getSold())
        ).collect(Collectors.toList());
    }

    // ==================== 管理后台 - 辅助方法 ====================

    private Map<Long, String> loadPromotionTitleMap(List<Long> promotionIds) {
        if (promotionIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SeckillPromotion> promotions = promotionMapper.selectBatchIds(promotionIds);
        return promotions.stream().collect(Collectors.toMap(SeckillPromotion::getId, SeckillPromotion::getTitle, (a, b) -> a));
    }

    private Map<Long, ItemDTO> loadItemMap(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            List<ItemDTO> items = itemClient.queryItemsByIds(productIds);
            if (items == null) {
                return Collections.emptyMap();
            }
            return items.stream().collect(Collectors.toMap(ItemDTO::getId, i -> i, (a, b) -> a));
        } catch (Exception e) {
            log.warn("批量查询商品信息失败, productIds={}", productIds, e);
            return Collections.emptyMap();
        }
    }

    private Map<Long, SeckillProductRelation> loadRelationMap(List<Long> relationIds) {
        if (relationIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SeckillProductRelation> relations = relationMapper.selectBatchIds(relationIds);
        return relations.stream().collect(Collectors.toMap(SeckillProductRelation::getId, r -> r, (a, b) -> a));
    }

    private SeckillProductRelationAdminVO buildRelationAdminVO(SeckillProductRelation r, Map<Long, ItemDTO> itemMap) {
        SeckillProductRelationAdminVO vo = new SeckillProductRelationAdminVO()
                .setId(r.getId())
                .setPromotionId(r.getPromotionId())
                .setSessionId(r.getSessionId())
                .setProductId(r.getProductId())
                .setSeckillPrice(r.getSeckillPrice())
                .setStock(r.getStock())
                .setLimitNum(r.getLimitNum())
                .setCreateTime(r.getCreateTime())
                .setUpdateTime(r.getUpdateTime());

        ItemDTO item = itemMap.get(r.getProductId());
        if (item != null) {
            vo.setProductName(item.getName());
            vo.setProductImage(item.getImage());
        }

        // Redis 实时库存
        String stockKey = STOCK_KEY_PREFIX + r.getId();
        Object stockObj = redisService.get(stockKey);
        if (stockObj != null) {
            try {
                int remaining = Integer.parseInt(stockObj.toString());
                vo.setRemainingStock(remaining);
                vo.setSoldCount(r.getStock() - remaining);
                vo.setPreheated(true);
            } catch (NumberFormatException e) {
                vo.setRemainingStock(r.getStock());
                vo.setSoldCount(0);
                vo.setPreheated(false);
            }
        } else {
            vo.setRemainingStock(r.getStock());
            vo.setSoldCount(0);
            vo.setPreheated(false);
        }

        return vo;
    }

    private void deleteSessionCascade(Long sessionId) {
        List<SeckillProductRelation> relations = relationMapper.selectList(
                new LambdaQueryWrapper<SeckillProductRelation>().eq(SeckillProductRelation::getSessionId, sessionId));
        for (SeckillProductRelation relation : relations) {
            clearRelationCache(relation.getId());
        }
        if (!relations.isEmpty()) {
            List<Long> relationIds = relations.stream().map(SeckillProductRelation::getId).collect(Collectors.toList());
            relationMapper.delete(new LambdaQueryWrapper<SeckillProductRelation>()
                    .eq(SeckillProductRelation::getSessionId, sessionId));
            dailyStockMapper.delete(new LambdaQueryWrapper<SeckillDailyStock>()
                    .in(SeckillDailyStock::getRelationId, relationIds));
        }
        sessionMapper.deleteById(sessionId);
    }

    private void clearRelationCache(Long relationId) {
        try {
            redisService.delete(STOCK_KEY_PREFIX + relationId);
            redisService.delete(LIMIT_KEY_PREFIX + relationId);
        } catch (Exception e) {
            log.warn("清除秒杀Redis缓存失败, relationId={}", relationId, e);
        }
    }
}
