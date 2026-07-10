package com.hmall.cart.task;

import com.hmall.cart.domain.po.Cart;
import com.hmall.cart.mapper.CartMapper;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.CollUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 购物车 Redis-MySQL 补偿同步任务（每 5 分钟执行）
 * <p>
 * 当 MQ 异步落库失败时，通过版本比对发现不一致并补偿同步。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartSyncCompensationTask {

    private final CartMapper cartMapper;
    private final RedisService redisService;

    private static final String CART_KEY_PREFIX = "cart:user:";
    private static final String CART_NUM_KEY_SUFFIX = ":num";
    private static final String CART_VERSION_KEY_SUFFIX = ":v";
    private static final long CART_TTL_DAYS = 30;

    /**
     * 每 5 分钟执行一次补偿同步
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void compensate() {
        log.info("购物车补偿同步任务开始");
        try {
            // 1. 获取所有有购物车数据的活跃用户
            List<Long> userIds = getActiveUserIds();
            if (CollUtils.isEmpty(userIds)) {
                log.info("无活跃购物车用户，补偿任务跳过");
                return;
            }

            int syncedCount = 0;
            for (Long userId : userIds) {
                try {
                    if (syncUserCartIfNeeded(userId)) {
                        syncedCount++;
                    }
                } catch (Exception e) {
                    log.error("补偿同步用户购物车失败, userId={}", userId, e);
                }
            }
            log.info("购物车补偿同步任务完成，已同步 {} / {} 个用户", syncedCount, userIds.size());
        } catch (Exception e) {
            log.error("购物车补偿同步任务异常", e);
        }
    }

    /**
     * 获取有购物车数据的活跃用户（从 MySQL cart 表查）
     */
    private List<Long> getActiveUserIds() {
        List<Cart> carts = cartMapper.selectList(null);
        return carts.stream()
                .map(Cart::getUserId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 比对版本号，不一致则同步
     *
     * @return true 执行了同步操作
     */
    private boolean syncUserCartIfNeeded(Long userId) {
        String cartKey = CART_KEY_PREFIX + userId;
        String numKey = cartKey + CART_NUM_KEY_SUFFIX;
        String versionKey = cartKey + CART_VERSION_KEY_SUFFIX;

        // 获取 Redis 版本号（不存在则视为 0）
        String redisVersionStr = (String) redisService.get(versionKey);
        long redisVersion = parseVersion(redisVersionStr);

        // 获取 MySQL 最大版本号
        Long mysqlMaxVersion = getMysqlMaxVersion(userId);

        if (redisVersion > 0 && (mysqlMaxVersion == null || redisVersion > mysqlMaxVersion)) {
            // Redis 版本更高 → Redis → MySQL 全量同步
            syncRedisToMysql(userId, cartKey, numKey);
            return true;
        } else if (redisVersion == 0 && mysqlMaxVersion != null) {
            // Redis 为空，MySQL 有数据 → MySQL → Redis 回填
            syncMysqlToRedis(userId, cartKey, numKey, versionKey);
            return true;
        }
        return false;
    }

    /**
     * Redis → MySQL 全量覆盖
     */
    private void syncRedisToMysql(Long userId, String cartKey, String numKey) {
        Map<Object, Object> cartMap = redisService.hGetAll(cartKey);
        Map<Object, Object> numMap = redisService.hGetAll(numKey);

        if (cartMap == null || cartMap.isEmpty()) {
            return;
        }

        // 先删 MySQL 旧数据
        cartMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Cart>()
                .lambda().eq(Cart::getUserId, userId));

        // 重新插入 Redis 中的所有条目
        long now = System.currentTimeMillis();
        for (Map.Entry<Object, Object> entry : cartMap.entrySet()) {
            Long itemId = Long.valueOf(entry.getKey().toString());
            Object numObj = numMap != null ? numMap.get(entry.getKey()) : null;
            int num = numObj != null ? Integer.parseInt(numObj.toString()) : 1;

            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setItemId(itemId);
            cart.setNum(num);
            cart.setVersion(now);

            // 从 Redis JSON 中提取商品信息
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemData = (Map<String, Object>) entry.getValue();
                cart.setName((String) itemData.get("name"));
                cart.setSpec((String) itemData.get("spec"));
                cart.setPrice(toInt(itemData.get("price")));
                cart.setImage((String) itemData.get("image"));
            }
            cartMapper.insert(cart);
        }
        log.info("补偿同步 Redis→MySQL 完成，userId={}, count={}", userId, cartMap.size());
    }

    /**
     * MySQL → Redis 全量回填
     */
    private void syncMysqlToRedis(Long userId, String cartKey, String numKey, String versionKey) {
        List<Cart> mysqlCarts = cartMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Cart>()
                        .lambda().eq(Cart::getUserId, userId));

        if (CollUtils.isEmpty(mysqlCarts)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Cart cart : mysqlCarts) {
            String itemId = String.valueOf(cart.getItemId());
            Map<String, Object> itemData = cartToItemDataMap(cart, now);
            redisService.hSet(cartKey, itemId, itemData);
            redisService.hSet(numKey, itemId, String.valueOf(cart.getNum()));
        }
        redisService.set(versionKey, String.valueOf(now), CART_TTL_DAYS, TimeUnit.DAYS);
        redisService.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
        redisService.expire(numKey, CART_TTL_DAYS, TimeUnit.DAYS);
        log.info("补偿同步 MySQL→Redis 完成，userId={}, count={}", userId, mysqlCarts.size());
    }

    private Long getMysqlMaxVersion(Long userId) {
        List<Cart> carts = cartMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Cart>()
                        .lambda().eq(Cart::getUserId, userId)
                        .orderByDesc(Cart::getVersion)
                        .last("LIMIT 1"));
        if (CollUtils.isEmpty(carts) || carts.get(0).getVersion() == null) {
            return null;
        }
        return carts.get(0).getVersion();
    }

    private long parseVersion(String str) {
        if (str == null || str.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private java.util.Map<String, Object> cartToItemDataMap(Cart cart, long version) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("itemId", cart.getItemId());
        data.put("name", cart.getName());
        data.put("spec", cart.getSpec());
        data.put("price", cart.getPrice());
        data.put("image", cart.getImage());
        data.put("userId", cart.getUserId());
        data.put("ver", version);
        return data;
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.valueOf(value.toString());
    }
}
