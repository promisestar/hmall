package com.hmall.item.service.impl;

import com.hmall.api.client.SearchClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.utils.BeanUtils;
import com.hmall.item.domain.dto.RecommendItemDTO;
import com.hmall.item.domain.dto.RecommendVO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.mapper.RecommendMapper;
import com.hmall.item.service.IItemService;
import com.hmall.item.service.IRecommendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 个性化推荐服务实现
 * <p>
 * 三步管线：SQL 聚合偏好 → Feign 调用 search-service ES 召回 → MySQL 补充库存/状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements IRecommendService {

    private final RecommendMapper recommendMapper;
    private final SearchClient searchClient;
    private final IItemService itemService;

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
            // scene=home/cart: SQL 聚合用户偏好
            List<Map<String, Object>> catPrefs = recommendMapper.queryUserCategoryPreferences(userId);
            if (catPrefs != null) {
                for (Map<String, Object> pref : catPrefs) {
                    Object cat = pref.get("category");
                    if (cat != null) categories.add(cat.toString());
                }
            }
            List<Map<String, Object>> brandPrefs = recommendMapper.queryUserBrandPreferences(userId);
            if (brandPrefs != null) {
                for (Map<String, Object> pref : brandPrefs) {
                    Object brand = pref.get("brand");
                    if (brand != null) topBrands.add(brand.toString());
                }
            }
            List<Long> purchasedIds = recommendMapper.queryPurchasedItemIds(userId);
            if (purchasedIds != null) excludeIds = purchasedIds;
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
