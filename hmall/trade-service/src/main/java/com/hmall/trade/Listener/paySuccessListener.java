package com.hmall.trade.Listener;

import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderDetail;
import com.hmall.trade.service.IOrderDetailService;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ClassName: paySuccessListener
 * Package: com.hmall.trade.Listener
 * Description: 支付成功消息监听器
 *
 * @Author Raiden
 * @Create 2025/11/20 21:16
 * @Version 1.0
 *
 * Phase 2 扩展：支付成功后增量写入用户画像（Redis HINCRBY），
 * 与 Agent 侧 profile_store 共享同一份画像数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class paySuccessListener {

    private final IOrderService orderService;
    private final IOrderDetailService detailService;
    private final ItemClient itemClient;
    private final StringRedisTemplate stringRedisTemplate;

    // 画像 Redis Key 前缀（与 Agent 侧 src/profile/store.py 保持一致）
    private static final String PROFILE_PREFIX = "profile:";
    // 购买行为权重（与 Phase 1 _accumulate_preference weight=5 一致）
    private static final int PURCHASE_WEIGHT = 5;
    // 画像 TTL 30 天
    private static final int PROFILE_TTL_DAYS = 30;
    // 价格记录最大保留条数
    private static final int PRICE_MAX_LEN = 20;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "trade.pay.success.queue", durable = "true"),
            exchange = @Exchange(name = "pay.direct"),
            key = "pay.success"
    ))
    public void listenPaySuccess(Long orderId) {
        try {
            // 1. 查询订单
            Order order = orderService.getById(orderId);
            // 2. 若订单不为未支付状态，则忽略
            if (order == null || order.getStatus() != 1)
                return;
            orderService.markOrderPaySuccess(orderId);

            // 3. Phase 2: 增量写入用户画像（失败不影响支付流程）
            writePurchaseProfile(order);
        } catch (Exception e) {
            log.error("处理支付成功消息失败，orderId={}", orderId, e);
        }
    }

    /**
     * 支付成功后增量写入用户画像。
     * <p>
     * 使用 StringRedisTemplate 确保 hash field/value 为 plain string，
     * 与 Agent 侧 redis.asyncio 读写兼容（RedisService 的 redisTemplate 用 Jackson
     * 序列化会导致 field 带 JSON 引号，与 Agent 侧不兼容）。
     * <p>
     * 异常时仅 log.warn 不抛出，保证画像写入失败不影响支付流程。
     */
    private void writePurchaseProfile(Order order) {
        try {
            Long userId = order.getUserId();
            if (userId == null) {
                return;
            }

            // 1. 查订单详情获取 itemId + num
            List<OrderDetail> details = detailService.lambdaQuery()
                    .eq(OrderDetail::getOrderId, order.getId())
                    .list();
            if (details == null || details.isEmpty()) {
                return;
            }

            // 2. Feign 查商品获取 category/brand/price
            Set<Long> itemIds = details.stream()
                    .map(OrderDetail::getItemId)
                    .collect(Collectors.toSet());
            List<ItemDTO> items = itemClient.queryItemsByIds(itemIds);
            if (items == null || items.isEmpty()) {
                return;
            }

            // 构建 itemId → num 映射
            Map<Long, Integer> numMap = details.stream()
                    .collect(Collectors.toMap(
                            OrderDetail::getItemId,
                            OrderDetail::getNum,
                            Integer::sum));

            String prefix = PROFILE_PREFIX + userId;
            byte[] categoriesKeyB = (prefix + ":categories").getBytes(StandardCharsets.UTF_8);
            byte[] brandsKeyB = (prefix + ":brands").getBytes(StandardCharsets.UTF_8);
            byte[] pricesKeyB = (prefix + ":prices").getBytes(StandardCharsets.UTF_8);
            byte[] statsKeyB = (prefix + ":stats").getBytes(StandardCharsets.UTF_8);
            long ttlSeconds = PROFILE_TTL_DAYS * 24 * 3600L;

            // 3. 使用 pipeline 批量执行所有 Redis 操作（1 次网络往返）
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (ItemDTO item : items) {
                    int num = numMap.getOrDefault(item.getId(), 1);
                    int score = PURCHASE_WEIGHT * num;

                    if (item.getCategory() != null && !item.getCategory().isEmpty()) {
                        connection.hashCommands().hIncrBy(categoriesKeyB,
                                item.getCategory().getBytes(StandardCharsets.UTF_8), score);
                    }
                    if (item.getBrand() != null && !item.getBrand().isEmpty()) {
                        connection.hashCommands().hIncrBy(brandsKeyB,
                                item.getBrand().getBytes(StandardCharsets.UTF_8), score);
                    }
                    if (item.getPrice() != null) {
                        connection.listCommands().lPush(pricesKeyB,
                                String.valueOf(item.getPrice()).getBytes(StandardCharsets.UTF_8));
                    }
                }

                // expire + trim（每个 key 仅执行一次，而非每商品一次）
                connection.keyCommands().expire(categoriesKeyB, ttlSeconds);
                connection.keyCommands().expire(brandsKeyB, ttlSeconds);
                connection.listCommands().lTrim(pricesKeyB, 0, PRICE_MAX_LEN - 1);
                connection.keyCommands().expire(pricesKeyB, ttlSeconds);

                // 统计信息
                connection.hashCommands().hIncrBy(statsKeyB,
                        "purchase_count".getBytes(StandardCharsets.UTF_8), 1);
                connection.hashCommands().hSet(statsKeyB,
                        "last_update".getBytes(StandardCharsets.UTF_8),
                        String.valueOf(System.currentTimeMillis() / 1000).getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().expire(statsKeyB, ttlSeconds);

                return null;
            });

            log.debug("用户画像写入成功, userId={}, orderId={}", userId, order.getId());
        } catch (Exception e) {
            log.warn("画像写入失败，不影响支付流程, orderId={}", order.getId(), e);
        }
    }
}
