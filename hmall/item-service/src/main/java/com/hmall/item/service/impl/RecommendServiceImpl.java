package com.hmall.item.service.impl;

import com.hmall.api.client.SearchClient;
import com.hmall.api.client.TradeClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.utils.BeanUtils;
import com.hmall.item.domain.dto.RecommendItemDTO;
import com.hmall.item.domain.dto.RecommendVO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.service.IItemService;
import com.hmall.item.service.IRecommendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 个性化推荐服务实现
 * <p>
 * 三步管线：Feign 获取已购商品 → 聚合偏好 → Feign 调用 search-service ES 召回 → MySQL 补充库存/状态
 * <p>
 * 偏好聚合在 item-service 侧完成：trade-service 返回已购 itemId+num，
 * item-service 查 item 表补充 category/brand 后按购买数量加权聚合 Top3。
 * <p>
 * Phase 2 扩展：偏好聚合优先读 Redis 画像（profile:{userId}:categories/brands），
 * 命中时跳过 Feign 聚合（0 次 Feign 调用），miss 降级原全量聚合逻辑。
 * 画像由 Agent 侧 record_event 和后端 paySuccessListener 共同写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements IRecommendService {

    private final TradeClient tradeClient;
    private final SearchClient searchClient;
    private final IItemService itemService;
    private final StringRedisTemplate stringRedisTemplate;

    // 画像 Redis Key 前缀（与 Agent 侧 src/profile/store.py 保持一致）
    private static final String PROFILE_PREFIX = "profile:";

    @Override
    public RecommendVO recommend(Long userId, String scene, Integer size, Long itemId) {
        // 参数校验
        if (size == null || size <= 0) size = 10;
        if (scene == null) scene = "home";

        List<String> categories = new ArrayList<>();
        List<String> topBrands = new ArrayList<>();
        List<Long> excludeIds = new ArrayList<>();

        // 1. 确定召回参数
        if ("detail".equals(scene) && itemId != null) {
            // scene=detail: 获取种子商品的类目
            Item seedItem = itemService.getById(itemId);
            if (seedItem != null && seedItem.getCategory() != null) {
                categories.add(seedItem.getCategory());
            }
            excludeIds.add(itemId);
        } else if (userId != null) {
            // Phase 2: 优先读 Redis 画像（命中时偏好聚合 0 次 Feign 调用）
            String catKey = PROFILE_PREFIX + userId + ":categories";
            Map<Object, Object> redisCatScores = stringRedisTemplate.opsForHash().entries(catKey);

            if (redisCatScores != null && !redisCatScores.isEmpty()) {
                // 画像命中：直接使用 Redis 聚合偏好
                categories = topNFromStringMap(redisCatScores, 3);
                String brandKey = PROFILE_PREFIX + userId + ":brands";
                Map<Object, Object> redisBrandScores = stringRedisTemplate.opsForHash().entries(brandKey);
                topBrands = topNFromStringMap(redisBrandScores, 3);
                // excludeIds 仍需 Feign（已购列表不存画像）
                List<OrderDetailDTO> purchasedItems = safeQueryPurchasedItems();
                if (purchasedItems != null && !purchasedItems.isEmpty()) {
                    excludeIds = purchasedItems.stream()
                            .map(OrderDetailDTO::getItemId)
                            .collect(Collectors.toList());
                }
            } else {
                // 画像 miss：降级原全量聚合逻辑（Phase 1 代码不变）
                List<OrderDetailDTO> purchasedItems = safeQueryPurchasedItems();
                if (purchasedItems != null && !purchasedItems.isEmpty()) {
                    // 收集已购商品 ID（用于排除已购）
                    List<Long> itemIds = purchasedItems.stream()
                            .map(OrderDetailDTO::getItemId)
                            .collect(Collectors.toList());
                    excludeIds = new ArrayList<>(itemIds);

                    // 批量查询商品获取 category/brand
                    List<Item> purchasedItemList = itemService.listByIds(itemIds);
                    if (purchasedItemList != null && !purchasedItemList.isEmpty()) {
                        // 构建 itemId -> num 映射
                        Map<Long, Integer> numMap = purchasedItems.stream()
                                .collect(Collectors.toMap(
                                        OrderDetailDTO::getItemId,
                                        OrderDetailDTO::getNum,
                                        Integer::sum));

                        // 聚合类目/品牌偏好（按购买数量加权）
                        Map<String, Integer> catScores = new HashMap<>();
                        Map<String, Integer> brandScores = new HashMap<>();
                        for (Item item : purchasedItemList) {
                            Integer num = numMap.getOrDefault(item.getId(), 1);
                            if (item.getCategory() != null && !item.getCategory().isEmpty()) {
                                catScores.merge(item.getCategory(), num, Integer::sum);
                            }
                            if (item.getBrand() != null && !item.getBrand().isEmpty()) {
                                brandScores.merge(item.getBrand(), num, Integer::sum);
                            }
                        }
                        // 取 Top3
                        categories = topN(catScores, 3);
                        topBrands = topN(brandScores, 3);
                    }
                }
            }
        }

        // 2. Feign 调用 search-service ES 召回
        List<ItemDTO> searchResults = searchClient.recommend(
                categories.isEmpty() ? null : categories,
                excludeIds.isEmpty() ? null : excludeIds,
                size
        );
        if (searchResults == null) searchResults = new ArrayList<>();

        // 3. ES 无结果时 MySQL 热销兜底
        boolean isFallback = false;
        if (searchResults.isEmpty()) {
            searchResults = mysqlHotFallback(excludeIds, size);
            isFallback = true;
        }

        // 4. MySQL 批量补充 stock/status
        List<Long> resultIds = searchResults.stream().map(ItemDTO::getId).collect(Collectors.toList());
        Map<Long, Item> dbItemMap = new HashMap<>();
        if (!resultIds.isEmpty()) {
            List<Item> dbItems = itemService.listByIds(resultIds);
            if (dbItems != null) {
                dbItemMap = dbItems.stream().collect(Collectors.toMap(Item::getId, i -> i));
            }
        }

        // 5. 组装推荐商品，过滤已下架
        List<RecommendItemDTO> recommendItems = new ArrayList<>();
        for (ItemDTO searchItem : searchResults) {
            Item dbItem = dbItemMap.get(searchItem.getId());
            Integer status = dbItem != null ? dbItem.getStatus() : searchItem.getStatus();
            Integer stock = dbItem != null ? dbItem.getStock() : searchItem.getStock();

            // 过滤非在售商品
            if (status == null || status != 1) continue;

            RecommendItemDTO ri = new RecommendItemDTO();
            ri.setId(searchItem.getId());
            ri.setName(searchItem.getName());
            ri.setPrice(searchItem.getPrice());
            ri.setStock(stock);
            ri.setBrand(searchItem.getBrand());
            ri.setCategory(searchItem.getCategory());
            ri.setSold(searchItem.getSold());
            ri.setRecommendTags(generateTags(searchItem, categories, topBrands, scene, isFallback));
            recommendItems.add(ri);
        }

        // 6. 组装响应
        RecommendVO vo = new RecommendVO();
        vo.setList(recommendItems);
        vo.setTotal(recommendItems.size());

        // basedOn（冷启动兜底时不设 basedOn）
        if (!isFallback && (!categories.isEmpty() || !topBrands.isEmpty())) {
            RecommendVO.BasedOn basedOn = new RecommendVO.BasedOn();
            basedOn.setTopCategories(categories);
            basedOn.setTopBrands(topBrands);
            vo.setBasedOn(basedOn);
        }

        return vo;
    }

    /**
     * Feign 调用 trade-service 获取已购商品，异常时降级返回空列表
     */
    private List<OrderDetailDTO> safeQueryPurchasedItems() {
        try {
            return tradeClient.queryPurchasedItems();
        } catch (Exception e) {
            log.error("Feign 调用 trade-service 获取已购商品失败，降级为空列表", e);
            return new ArrayList<>();
        }
    }

    /**
     * MySQL 热销兜底：ES 不可用或无结果时，直接查 item 表按销量排序
     */
    private List<ItemDTO> mysqlHotFallback(List<Long> excludeIds, int size) {
        log.warn("ES 召回无结果，降级为 MySQL 热销兜底");
        List<Item> items = itemService.lambdaQuery()
                .eq(Item::getStatus, 1)
                .notIn(!excludeIds.isEmpty(), Item::getId, excludeIds)
                .orderByDesc(Item::getSold)
                .last("LIMIT " + size)
                .list();
        return BeanUtils.copyList(items, ItemDTO.class);
    }

    /**
     * 从得分 Map 中取 Top N
     */
    private List<String> topN(Map<String, Integer> scores, int n) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 从 Redis Hash entries（Map<Object, Object>，value 为 String 数字）取 Top N
     * <p>
     * 用于读取 StringRedisTemplate 存储的画像数据（与 Agent 侧 redis.asyncio 兼容）。
     */
    private List<String> topNFromStringMap(Map<Object, Object> scores, int n) {
        if (scores == null || scores.isEmpty()) {
            return new ArrayList<>();
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> {
                    int va = parseIntSafe(a.getValue());
                    int vb = parseIntSafe(b.getValue());
                    return Integer.compare(vb, va);
                })
                .limit(n)
                .map(e -> e.getKey().toString())
                .collect(Collectors.toList());
    }

    /**
     * 安全解析整数（Redis Hash value 为 String 类型）
     */
    private int parseIntSafe(Object value) {
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 生成推荐标签
     */
    private List<String> generateTags(ItemDTO item, List<String> categories,
                                       List<String> topBrands, String scene, boolean isFallback) {
        List<String> tags = new ArrayList<>();

        if (isFallback) {
            tags.add("热销推荐");
            return tags;
        }

        if ("detail".equals(scene)) {
            tags.add("相似推荐");
        } else if (categories.contains(item.getCategory())) {
            tags.add("同类目热销");
        }

        if (topBrands.contains(item.getBrand())) {
            tags.add("您常买的品牌");
        }

        if (tags.isEmpty()) {
            tags.add("热销推荐");
        }

        return tags;
    }
}
