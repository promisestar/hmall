package com.hmall.admin.feign.fallback;

import com.hmall.admin.feign.ItemFeignClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ItemFeignFallbackFactory implements FallbackFactory<ItemFeignClient> {
    @Override
    public ItemFeignClient create(Throwable cause) {
        log.error("调用 item-service 失败", cause);
        return new ItemFeignClient() {
            @Override
            public PageDTO<ItemDTO> queryItemByPage(PageQuery pageQuery) {
                return new PageDTO<>();
            }

            @Override
            public ItemDTO queryItemById(Long id) {
                return null;
            }

            @Override
            public void saveItem(ItemDTO item) {
            }

            @Override
            public void updateItem(ItemDTO item) {
            }

            @Override
            public void deleteItemById(Long id) {
            }

            @Override
            public void batchUpdateStatus(List<Long> ids, Integer status) {
            }

            @Override
            public void batchUpdateStock(Map<Long, Integer> stockMap) {
            }

            @Override
            public void batchDeleteItems(List<Long> ids) {
            }
        };
    }
}
