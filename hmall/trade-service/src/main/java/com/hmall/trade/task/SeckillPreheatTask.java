package com.hmall.trade.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmall.trade.domain.po.SeckillProductRelation;
import com.hmall.trade.domain.po.SeckillSession;
import com.hmall.trade.mapper.SeckillProductRelationMapper;
import com.hmall.trade.mapper.SeckillSessionMapper;
import com.hmall.trade.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀库存定时预热任务
 * <p>
 * 每分钟扫描即将开始（未来 5 分钟内）的秒杀场次，
 * 将场次下的商品库存预热到 Redis，并初始化每日库存快照。
 * <p>
 * 对应 3.7.4 节"活动开始前：预热"流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillPreheatTask {

    private final SeckillSessionMapper sessionMapper;
    private final SeckillProductRelationMapper relationMapper;
    private final SeckillService seckillService;

    /**
     * 每分钟执行一次，预热即将开始的场次
     */
    @Scheduled(fixedDelay = 60_000)
    public void preheat() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusMinutes(5);

        // 1. 查询未来 5 分钟内开始且尚未结束的场次
        List<SeckillSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<SeckillSession>()
                        .le(SeckillSession::getStartTime, windowEnd)
                        .gt(SeckillSession::getEndTime, now)
        );

        if (sessions.isEmpty()) {
            return;
        }

        log.info("秒杀预热任务开始, 待预热场次数={}", sessions.size());

        int preheatedCount = 0;
        for (SeckillSession session : sessions) {
            // 2. 查询该场次下的所有商品关联
            List<SeckillProductRelation> relations = relationMapper.selectList(
                    new LambdaQueryWrapper<SeckillProductRelation>()
                            .eq(SeckillProductRelation::getSessionId, session.getId())
            );

            for (SeckillProductRelation relation : relations) {
                try {
                    seckillService.preheat(relation.getId());
                    preheatedCount++;
                } catch (Exception e) {
                    log.error("秒杀预热失败, relationId={}", relation.getId(), e);
                }
            }
        }

        log.info("秒杀预热任务完成, 已预热商品数={}", preheatedCount);
    }
}
