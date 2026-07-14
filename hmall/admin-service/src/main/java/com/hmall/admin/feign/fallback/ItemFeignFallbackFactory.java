package com.hmall.admin.feign.fallback;

import com.hmall.admin.feign.ItemFeignClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
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
            public R<ItemDTO> queryItemById(Long id) {
                return R.error("商品服务暂时不可用");
            }

            @Override
            public R<Void> saveItem(ItemDTO item) {
                return R.error("商品服务暂时不可用");
            }

            @Override
            public R<Void> updateItem(ItemDTO item) {
                return R.error("商品服务暂时不可用");
            }

            @Override
            public R<Void> deleteItemById(Long id) {
                return R.error("商品服务暂时不可用");
            }

            @Override
            public R<Void> batchUpdateStatus(List<Long> ids, Integer status) {
                return R.error("商品服务暂时不可用");
            }

            @Override
            public R<Void> batchUpdateStock(Map<Long, Integer> stockMap) {
                return R.error("商品服务暂时不可用");
            }

            @Override
            public R<Void> batchDeleteItems(List<Long> ids) {
                return R.error("商品服务暂时不可用");
            }
        };
    }
}
