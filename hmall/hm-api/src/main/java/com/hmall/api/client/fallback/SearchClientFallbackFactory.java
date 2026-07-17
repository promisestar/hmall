package com.hmall.api.client.fallback;

import com.hmall.api.client.SearchClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * ClassName: SearchClientFallbackFactory
 * Package: com.hmall.api.client.fallback
 * Description: SearchClient 降级工厂。search-service 不可用时返回空列表，
 *              让 RecommendServiceImpl 降级为 MySQL 热销兜底。
 *
 * @Author CodeBuddy
 * @Create 2026/7/17
 * @Version 1.0
 */
@Slf4j
public class SearchClientFallbackFactory implements FallbackFactory<SearchClient> {

    @Override
    public SearchClient create(Throwable cause) {
        return new SearchClient() {
            @Override
            public List<ItemDTO> recommend(List<String> categories, List<Long> excludeIds, Integer size) {
                log.error("ES 推荐召回失败，降级返回空列表", cause);
                return CollUtils.emptyList();
            }
        };
    }
}
