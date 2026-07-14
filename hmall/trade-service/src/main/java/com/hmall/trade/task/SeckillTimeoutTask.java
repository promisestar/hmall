package com.hmall.trade.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmall.trade.domain.po.SeckillOrder;
import com.hmall.trade.mapper.SeckillOrderMapper;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀超时兜底回补任务
 * <p>
 * 每 5 分钟扫描超时未支付的秒杀订单（超过 30 分钟），
 * 关单并回补 Redis 库存、MySQL 库存、限购额度。
 * <p>
 * 这是延迟消息机制的兜底：当 orderDelayMessageListener 遗漏或处理失败时，
 * 本任务确保超时订单最终被关闭、库存最终被回补。
 * <p>
 * 对应 3.7.4 节"超时未支付"流程的兜底实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillTimeoutTask {

    private final SeckillOrderMapper seckillOrderMapper;
    private final IOrderService orderService;

    /**
     * 每 5 分钟执行一次，初始延迟 2 分钟（等待服务启动完成）
     */
    @Scheduled(fixedDelay = 5 * 60_000, initialDelay = 2 * 60_000)
    public void closeTimeoutOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);

        // 1. 查询超时未支付的秒杀订单（status=1 且创建时间超过 30 分钟）
        List<SeckillOrder> timeoutOrders = seckillOrderMapper.selectList(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getStatus, 1)
                        .lt(SeckillOrder::getCreateTime, cutoff)
        );

        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("秒杀超时兜底任务开始, 待处理订单数={}", timeoutOrders.size());

        int closedCount = 0;
        for (SeckillOrder seckillOrder : timeoutOrders) {
            try {
                // 2. 调用 cancelOrder 关单（会自动判断秒杀订单并回补库存）
                orderService.cancelOrder(seckillOrder.getOrderId());
                closedCount++;
            } catch (Exception e) {
                log.error("秒杀超时关单失败, orderId={}", seckillOrder.getOrderId(), e);
            }
        }

        log.info("秒杀超时兜底任务完成, 已关闭订单数={}", closedCount);
    }
}
