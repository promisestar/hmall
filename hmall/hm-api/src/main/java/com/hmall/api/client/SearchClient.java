package com.hmall.api.client;

import com.hmall.api.client.fallback.SearchClientFallbackFactory;
import com.hmall.api.dto.ItemDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * ClassName: SearchClient
 * Package: com.hmall.api.client
 * Description: search-service 的 Feign 客户端，用于 ES 商品召回
 *
 * @Author CodeBuddy
 * @Create 2026/7/17
 * @Version 1.0
 */
@FeignClient(value = "search-service", fallbackFactory = SearchClientFallbackFactory.class)
public interface SearchClient {

    /**
     * 推荐商品 ES 召回：按类目过滤 + 排除已购 + 销量排序
     *
     * @param categories  偏好类目列表（为空时走热销兜底）
     * @param excludeIds  需排除的商品 ID 列表（已购商品）
     * @param size        返回数量
     * @return            商品列表（stock/status 字段为空，需调用方补充）
     */
    @GetMapping("/search/recommend")
    List<ItemDTO> recommend(
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
            @RequestParam("size") Integer size
    );
}
