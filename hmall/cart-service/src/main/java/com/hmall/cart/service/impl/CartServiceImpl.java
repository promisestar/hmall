package com.hmall.cart.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;

import com.hmall.cart.config.CartProperties;
import com.hmall.cart.domain.dto.CartFormDTO;
import com.hmall.cart.domain.dto.CartSyncMessage;
import com.hmall.cart.domain.po.Cart;
import com.hmall.cart.domain.vo.CartVO;
import com.hmall.cart.mapper.CartMapper;
import com.hmall.cart.mq.CartSyncSender;
import com.hmall.cart.service.ICartService;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.common.utils.LuaScriptLoader;
import com.hmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 订单详情表 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    private final ItemClient itemClient;
    private final CartProperties cartProperties;
    private final RedisService redisService;
    private final CartSyncSender cartSyncSender;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String CART_KEY_PREFIX = "cart:user:";
    private static final String CART_NUM_KEY_SUFFIX = ":num";
    private static final String CART_VERSION_KEY_SUFFIX = ":v";
    private static final long CART_TTL_DAYS = 30;

    // 画像 Redis Key 前缀（与 Agent 侧 src/profile/store.py 保持一致）
    private static final String PROFILE_PREFIX = "profile:";
    // 购物车行为权重（与 Phase 1 _accumulate_preference weight=3 一致）
    private static final int CART_WEIGHT = 3;
    // 画像 TTL 30 天
    private static final int PROFILE_TTL_DAYS = 30;
    // 价格记录最大保留条数
    private static final int PRICE_MAX_LEN = 20;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 待失效用户标记（Redis 宕机导致 invalidateRedisCart 失败时记录）
     * <p>
     * 正常操作下此集合为空，查询时 O(1) 检查不产生任何 I/O 开销。
     * 仅在 Redis 曾宕机且降级写 MySQL 的用户查询时触发一次重新加载，
     * 完成后自动移除标记，后续查询恢复纯 Redis 快速路径。
     * <p>
     * 多实例/重启场景由补偿任务（@5min）兜底。
     */
    private final Set<Long> pendingInvalidationUsers = ConcurrentHashMap.newKeySet();

    // ==================== Lua 脚本 ====================

    /**
     * 原子加购 Lua 脚本：
     *   - 已存在 → HINCRBY 数量（原子递增，无竞态）
     *   - 不存在 → 检查 HLEN 上限 → HSET 新条目
     *   - 统一设置过期时间
     * 返回：-1 = 购物车已满，>= 0 = 当前数量
     */
    private static final String ADD_CART_LUA = LuaScriptLoader.load("lua/add_cart.lua");

    /**
     * 原子删除购物车条目 Lua 脚本：同时清理 item 数据和 num
     */
    private static final String REMOVE_CART_LUA = LuaScriptLoader.load("lua/remove_cart.lua");

    private String buildCartKey(Long userId) {
        return CART_KEY_PREFIX + userId;
    }

    private String buildCartNumKey(Long userId) {
        return CART_KEY_PREFIX + userId + CART_NUM_KEY_SUFFIX;
    }

    private String buildCartVersionKey(Long userId) {
        return CART_KEY_PREFIX + userId + CART_VERSION_KEY_SUFFIX;
    }

    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        Long userId = UserContext.getUser();
        String cartKey = buildCartKey(userId);
        String numKey = buildCartNumKey(userId);
        String versionKey = buildCartVersionKey(userId);
        String fieldKey = String.valueOf(cartFormDTO.getItemId());

        try {
            // 冷启动兜底：Redis 为空时先同步 MySQL 历史数据
            ensureCartRedisSynced(userId);

            // 构建商品数据（含 ver 版本字段）
            long version = System.currentTimeMillis();
            Map<String, Object> itemData = buildCartItemData(cartFormDTO, userId);
            itemData.put("ver", version);
            String itemDataJson = objectMapper.writeValueAsString(itemData);
            long ttlSeconds = TimeUnit.DAYS.toSeconds(CART_TTL_DAYS);

            // Lua 原子执行：HEXISTS/HLEN 检查 → HSET/HINCRBY → SET version → EXPIRE
            // 注意：executeScript 底层使用 StringRedisTemplate，所有 args 必须为 String 类型
            // （StringRedisSerializer 期望 String，传 Long/Integer 会触发 ClassCastException）
            // Lua 端用 tonumber() 将字符串转为数字，所以传 String 不影响 tonumber 解析
            Long result = redisService.executeScript(
                    ADD_CART_LUA, Long.class,
                    Arrays.asList(cartKey, numKey, versionKey),
                    fieldKey, itemDataJson, String.valueOf(ttlSeconds),
                    String.valueOf(cartProperties.getMaxItems()),
                    String.valueOf(version)
            );

            if (result != null && result == -1) {
                throw new BizIllegalException(
                        StrUtil.format("用户购物车课程不能超过{}", cartProperties.getMaxItems()));
            }

            // Redis 写入成功后，MQ 异步通知落 MySQL（result 为 Lua 返回的实际数量）
            int actualNum = (result != null) ? result.intValue() : 1;

            // Phase 2: 增量写入用户画像（失败不影响加购流程，内部 try-catch）
            writeCartProfile(userId, cartFormDTO);

            CartSyncMessage syncMsg = toSyncMessage(cartFormDTO, userId, version, actualNum);
            cartSyncSender.sendSync(syncMsg);
        } catch (BizIllegalException e) {
            throw e;
        } catch (Exception e) {
            // Redis 不可用，降级到纯 MySQL
            log.warn("Redis 操作失败，降级到 MySQL 处理加购", e);
            addItem2CartMysql(cartFormDTO, userId);
            // 清除 Redis 旧缓存，防止下次查询读到过期数据（MySQL 已写入最新数据）
            // 下次查询时 Redis 为空 → 回退 MySQL → lazy sync 回填 Redis
            invalidateRedisCart(userId);
        }
    }

    @Override
    public List<CartVO> queryMyCarts() {
        Long userId = UserContext.getUser();
        String cartKey = buildCartKey(userId);
        String numKey = buildCartNumKey(userId);

        List<CartVO> vos;
        try {
            Map<Object, Object> cartMap = redisService.hGetAll(cartKey);
            Map<Object, Object> numMap = redisService.hGetAll(numKey);
            if (cartMap != null && !cartMap.isEmpty()) {
                // 检查待失效标记（内存 O(1)，无 I/O 开销）
                // 场景：Redis 宕机期间降级写 MySQL，Redis 恢复后旧数据仍在
                if (pendingInvalidationUsers.contains(userId)) {
                    log.info("检测到待失效标记，从 MySQL 重新加载购物车，userId={}", userId);
                    invalidateRedisCart(userId); // 再次尝试清除（Redis 可能已恢复）
                    vos = queryMyCartsMysql(userId);
                    if (CollUtils.isNotEmpty(vos)) {
                        syncCartsToRedis(userId, vos);
                    }
                } else {
                    // 正常路径：纯 Redis 读取，零 MySQL 查询
                    vos = convertRedisMapToCartVOList(cartMap, numMap);
                }
            } else {
                // Redis 为空 → 回退 MySQL + 回填 Redis（冷启动/lazy sync）
                log.info("Redis 购物车为空，从 MySQL 加载并回填，userId={}", userId);
                vos = queryMyCartsMysql(userId);
                if (CollUtils.isNotEmpty(vos)) {
                    syncCartsToRedis(userId, vos);
                }
            }
        } catch (Exception e) {
            log.warn("Redis 查询购物车失败，降级到 MySQL", e);
            vos = queryMyCartsMysql(userId);
        }

        if (CollUtils.isEmpty(vos)) {
            return CollUtils.emptyList();
        }
        handleCartItems(vos);
        return vos;
    }

    @Override
    @Transactional
    public void removeByItemIds(Collection<Long> itemIds) {
        Long userId = UserContext.getUser();
        removeByItemIds(itemIds, userId);
    }

    @Transactional
    public void removeByItemIds(Collection<Long> itemIds, Long userId) {
        String cartKey = buildCartKey(userId);
        String numKey = buildCartNumKey(userId);
        String versionKey = buildCartVersionKey(userId);

        // 1. Redis Lua 原子删除（双 Hash）+ 更新版本号
        boolean redisFailed = false;
        try {
            Object[] fields = itemIds.stream().map(String::valueOf).toArray();
            redisService.executeScript(REMOVE_CART_LUA, Long.class,
                    Arrays.asList(cartKey, numKey), fields);
            // 更新版本号，让补偿任务感知变更
            redisService.set(versionKey, String.valueOf(System.currentTimeMillis()), CART_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Redis 删除购物车条目失败", e);
            redisFailed = true;
        }

        // 2. MySQL 同步 DELETE（必须同步，不走 MQ，防止补偿任务回填）
        try {
            removeByItemIdsMysql(itemIds, userId);
            // Redis 删除失败但 MySQL 成功 → 清除 Redis 旧缓存，防止下次查询读到已删除的商品
            if (redisFailed) {
                invalidateRedisCart(userId);
            }
        } catch (Exception e) {
            log.error("MySQL 删除购物车条目失败, userId={}, itemIds={}", userId, itemIds, e);
        }
    }

    @Override
    public void updateCartNum(Long itemId, Integer num) {
        Long userId = UserContext.getUser();
        String cartKey = buildCartKey(userId);
        String numKey = buildCartNumKey(userId);
        String versionKey = buildCartVersionKey(userId);
        String fieldKey = String.valueOf(itemId);
        long version = System.currentTimeMillis();

        // 1. 更新 Redis
        try {
            ensureCartRedisSynced(userId);
            redisService.hSet(numKey, fieldKey, String.valueOf(num));
            redisService.set(versionKey, String.valueOf(version), CART_TTL_DAYS, TimeUnit.DAYS);
            redisService.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
            redisService.expire(numKey, CART_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Redis 更新购物车数量失败，降级到 MySQL", e);
            // 降级：直接更新 MySQL
            updateCartNumMysql(itemId, userId, num, version);
            invalidateRedisCart(userId);
            return;
        }

        // 2. MQ 异步同步 MySQL
        try {
            CartSyncMessage msg = new CartSyncMessage();
            msg.setUserId(userId);
            msg.setItemId(itemId);
            msg.setNum(num);
            msg.setVersion(version);
            cartSyncSender.sendSync(msg);
        } catch (Exception e) {
            log.warn("更新数量 MQ 同步失败，将由补偿任务兜底，userId={}, itemId={}", userId, itemId, e);
        }
    }

    private void updateCartNumMysql(Long itemId, Long userId, Integer num, long version) {
        if (checkItemExists(itemId, userId)) {
            baseMapper.updateNum(itemId, userId, version);
            // updateNum 只 +1，需要额外设置到目标值
            Cart cart = lambdaQuery()
                    .eq(Cart::getUserId, userId)
                    .eq(Cart::getItemId, itemId)
                    .one();
            if (cart != null) {
                cart.setNum(num);
                cart.setVersion(version);
                updateById(cart);
            }
        }
    }

    // ==================== Phase 2: 用户画像写入 ====================

    /**
     * 加购成功后增量写入用户画像。
     * <p>
     * 使用 StringRedisTemplate 确保 hash field/value 为 plain string，
     * 与 Agent 侧 redis.asyncio 读写兼容。
     * 异常时仅 log.warn 不抛出，保证画像写入失败不影响加购流程。
     */
    private void writeCartProfile(Long userId, CartFormDTO cartFormDTO) {
        try {
            // Feign 查商品获取 category/brand/price
            List<ItemDTO> items = itemClient.queryItemsByIds(Collections.singleton(cartFormDTO.getItemId()));
            if (items == null || items.isEmpty()) {
                return;
            }
            ItemDTO item = items.get(0);

            String prefix = PROFILE_PREFIX + userId;
            byte[] categoriesKeyB = (prefix + ":categories").getBytes(StandardCharsets.UTF_8);
            byte[] brandsKeyB = (prefix + ":brands").getBytes(StandardCharsets.UTF_8);
            byte[] pricesKeyB = (prefix + ":prices").getBytes(StandardCharsets.UTF_8);
            byte[] statsKeyB = (prefix + ":stats").getBytes(StandardCharsets.UTF_8);
            long ttlSeconds = PROFILE_TTL_DAYS * 24 * 3600L;
            int score = CART_WEIGHT; // cart 权重=3，num=1

            // 使用 pipeline 批量执行所有 Redis 操作（1 次网络往返）
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                if (item.getCategory() != null && !item.getCategory().isEmpty()) {
                    connection.hashCommands().hIncrBy(categoriesKeyB,
                            item.getCategory().getBytes(StandardCharsets.UTF_8), score);
                    connection.keyCommands().expire(categoriesKeyB, ttlSeconds);
                }
                if (item.getBrand() != null && !item.getBrand().isEmpty()) {
                    connection.hashCommands().hIncrBy(brandsKeyB,
                            item.getBrand().getBytes(StandardCharsets.UTF_8), score);
                    connection.keyCommands().expire(brandsKeyB, ttlSeconds);
                }
                if (item.getPrice() != null) {
                    connection.listCommands().lPush(pricesKeyB,
                            String.valueOf(item.getPrice()).getBytes(StandardCharsets.UTF_8));
                    connection.listCommands().lTrim(pricesKeyB, 0, PRICE_MAX_LEN - 1);
                    connection.keyCommands().expire(pricesKeyB, ttlSeconds);
                }

                // 统计信息
                connection.hashCommands().hIncrBy(statsKeyB,
                        "cart_count".getBytes(StandardCharsets.UTF_8), 1);
                connection.hashCommands().hSet(statsKeyB,
                        "last_update".getBytes(StandardCharsets.UTF_8),
                        String.valueOf(System.currentTimeMillis() / 1000).getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().expire(statsKeyB, ttlSeconds);

                return null;
            });

            log.debug("用户加购画像写入成功, userId={}, itemId={}", userId, cartFormDTO.getItemId());
        } catch (Exception e) {
            log.warn("加购画像写入失败，不影响加购流程, userId={}, itemId={}",
                    userId, cartFormDTO.getItemId(), e);
        }
    }

    // ==================== MySQL 降级方法 ====================

    private void addItem2CartMysql(CartFormDTO cartFormDTO, Long userId) {
        long version = System.currentTimeMillis();
        if (checkItemExists(cartFormDTO.getItemId(), userId)) {
            baseMapper.updateNum(cartFormDTO.getItemId(), userId, version);
            return;
        }
        checkCartsFull(userId);
        Cart cart = BeanUtils.copyBean(cartFormDTO, Cart.class);
        cart.setUserId(userId);
        cart.setVersion(version);
        save(cart);
    }

    private List<CartVO> queryMyCartsMysql(Long userId) {
        List<Cart> carts = lambdaQuery().eq(Cart::getUserId, userId).list();
        if (CollUtils.isEmpty(carts)) {
            return CollUtils.emptyList();
        }
        return BeanUtils.copyList(carts, CartVO.class);
    }

    private void removeByItemIdsMysql(Collection<Long> itemIds, Long userId) {
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, userId)
                .in(Cart::getItemId, itemIds);
        remove(queryWrapper);
    }

    // ==================== Redis-MySQL 同步方法 ====================

    /**
     * 清除用户购物车的 Redis 缓存（三个 Key：商品数据、数量、版本号）
     * <p>
     * Redis 可用时：立即清除，下次查询走 MySQL lazy sync 回填。<br>
     * Redis 不可用时：标记到 pendingInvalidationUsers，下次该用户查询时
     * 检测到标记 → 从 MySQL 重新加载 → 回填 Redis → 移除标记。
     */
    private void invalidateRedisCart(Long userId) {
        try {
            redisService.delete(buildCartKey(userId));
            redisService.delete(buildCartNumKey(userId));
            redisService.delete(buildCartVersionKey(userId));
            pendingInvalidationUsers.remove(userId); // 清除成功，移除待失效标记
            log.info("已清除 Redis 购物车缓存，userId={}", userId);
        } catch (Exception e) {
            // Redis 不可达，标记待失效，下次查询时重试
            pendingInvalidationUsers.add(userId);
            log.warn("清除 Redis 购物车缓存失败，已标记待失效，userId={}", userId, e);
        }
    }

    /**
     * 确保 Redis 中有用户购物车数据（冷启动 lazy sync）
     * 若 Redis 为空，从 MySQL 全量加载并回填
     */
    private void ensureCartRedisSynced(Long userId) {
        try {
            String cartKey = buildCartKey(userId);
            Long size = redisService.hLen(cartKey);
            if (size != null && size > 0) {
                return; // 已有数据，无需同步
            }
            // Redis 为空 → 从 MySQL 加载
            List<CartVO> mysqlCarts = queryMyCartsMysql(userId);
            if (CollUtils.isNotEmpty(mysqlCarts)) {
                syncCartsToRedis(userId, mysqlCarts);
                log.info("购物车冷启动同步完成，userId={}, count={}", userId, mysqlCarts.size());
            }
        } catch (Exception e) {
            log.warn("购物车冷启动同步失败，userId={}", userId, e);
        }
    }

    /**
     * 将 MySQL 购物车数据全量回填 Redis（含版本号）
     */
    private void syncCartsToRedis(Long userId, List<CartVO> carts) {
        String cartKey = buildCartKey(userId);
        String numKey = buildCartNumKey(userId);
        String versionKey = buildCartVersionKey(userId);
        long version = System.currentTimeMillis();

        for (CartVO cart : carts) {
            Map<String, Object> itemData = cartVoToItemData(cart, userId, version);
            redisService.hSet(cartKey, String.valueOf(cart.getItemId()), itemData);
            redisService.hSet(numKey, String.valueOf(cart.getItemId()), String.valueOf(cart.getNum()));
        }
        redisService.set(versionKey, String.valueOf(version), CART_TTL_DAYS, TimeUnit.DAYS);
        redisService.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
        redisService.expire(numKey, CART_TTL_DAYS, TimeUnit.DAYS);
    }

    private CartSyncMessage toSyncMessage(CartFormDTO dto, Long userId, long version, int actualNum) {
        CartSyncMessage msg = new CartSyncMessage();
        msg.setUserId(userId);
        msg.setItemId(dto.getItemId());
        msg.setName(dto.getName());
        msg.setSpec(dto.getSpec());
        msg.setPrice(dto.getPrice());
        msg.setImage(dto.getImage());
        msg.setNum(actualNum);
        msg.setVersion(version);
        return msg;
    }

    private Map<String, Object> cartVoToItemData(CartVO cart, Long userId, long version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", cart.getItemId());
        data.put("name", cart.getName());
        data.put("spec", cart.getSpec());
        data.put("price", cart.getPrice());
        data.put("image", cart.getImage());
        data.put("userId", userId);
        data.put("ver", version);
        return data;
    }

    // ==================== Redis 辅助方法 ====================

    private Map<String, Object> buildCartItemData(CartFormDTO dto, Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", dto.getItemId());
        data.put("name", dto.getName());
        data.put("spec", dto.getSpec());
        data.put("price", dto.getPrice());
        data.put("image", dto.getImage());
        data.put("userId", userId);
        data.put("createTime", LocalDateTime.now().toString());
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<CartVO> convertRedisMapToCartVOList(Map<Object, Object> cartMap, Map<Object, Object> numMap) {
        List<CartVO> vos = new ArrayList<>(cartMap.size());
        for (Map.Entry<Object, Object> entry : cartMap.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> itemMap = (Map<String, Object>) entry.getValue();
                CartVO vo = new CartVO();
                vo.setItemId(toLong(itemMap.get("itemId")));
                // Redis 不存储 MySQL 自增主键，用 itemId 作为唯一标识供前端
                // toggleCheck/v-for key 等操作需要非 null 的 id 来区分不同商品
                vo.setId(vo.getItemId());
                vo.setName((String) itemMap.get("name"));
                vo.setSpec((String) itemMap.get("spec"));
                vo.setPrice(toInt(itemMap.get("price")));
                vo.setImage((String) itemMap.get("image"));
                // 从独立 num Hash 获取数量（HINCRBY 原子管理）
                Object numObj = numMap != null ? numMap.get(entry.getKey()) : null;
                vo.setNum(toInt(numObj));
                vos.add(vo);
            }
        }
        return vos;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.valueOf(value.toString());
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.valueOf(value.toString());
    }

    // ==================== 原有 MySQL 辅助方法（保留用于降级） ====================

    private void handleCartItems(List<CartVO> vos) {
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
        List<ItemDTO> items;
        try {
            items = itemClient.queryItemsByIds(itemIds);
        } catch (Exception e) {
            log.error("查询商品信息失败，itemIds={}", itemIds, e);
            return;
        }
        if (CollUtils.isEmpty(items)) {
            return;
        }
        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity(), (k1, k2) -> k1));
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    private void checkCartsFull(Long userId) {
        int count = lambdaQuery().eq(Cart::getUserId, userId).count();
        if (count >= cartProperties.getMaxItems()) {
            throw new BizIllegalException(StrUtil.format("用户购物车课程不能超过{}", cartProperties.getMaxItems()));
        }
    }

    private boolean checkItemExists(Long itemId, Long userId) {
        int count = lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getItemId, itemId)
                .count();
        return count > 0;
    }
}
