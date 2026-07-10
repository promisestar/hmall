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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
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

    private static final String CART_KEY_PREFIX = "cart:user:";
    private static final String CART_NUM_KEY_SUFFIX = ":num";
    private static final String CART_VERSION_KEY_SUFFIX = ":v";
    private static final long CART_TTL_DAYS = 30;

    private static final ObjectMapper objectMapper = new ObjectMapper();

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
            CartSyncMessage syncMsg = toSyncMessage(cartFormDTO, userId, version, actualNum);
            cartSyncSender.sendSync(syncMsg);
        } catch (BizIllegalException e) {
            throw e;
        } catch (Exception e) {
            // Redis 不可用，降级到纯 MySQL
            log.warn("Redis 操作失败，降级到 MySQL 处理加购", e);
            addItem2CartMysql(cartFormDTO, userId);
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
                vos = convertRedisMapToCartVOList(cartMap, numMap);
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
        try {
            Object[] fields = itemIds.stream().map(String::valueOf).toArray();
            redisService.executeScript(REMOVE_CART_LUA,
                    Arrays.asList(cartKey, numKey), fields);
            // 更新版本号，让补偿任务感知变更
            redisService.set(versionKey, String.valueOf(System.currentTimeMillis()), CART_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Redis 删除购物车条目失败", e);
        }

        // 2. MySQL 同步 DELETE（必须同步，不走 MQ，防止补偿任务回填）
        try {
            removeByItemIdsMysql(itemIds, userId);
        } catch (Exception e) {
            log.error("MySQL 删除购物车条目失败, userId={}, itemIds={}", userId, itemIds, e);
        }
    }

    // ==================== MySQL 降级方法 ====================

    private void addItem2CartMysql(CartFormDTO cartFormDTO, Long userId) {
        if (checkItemExists(cartFormDTO.getItemId(), userId)) {
            baseMapper.updateNum(cartFormDTO.getItemId(), userId);
            return;
        }
        checkCartsFull(userId);
        Cart cart = BeanUtils.copyBean(cartFormDTO, Cart.class);
        cart.setUserId(userId);
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
