package com.hmall.api.client.fallback;

import cn.hutool.core.collection.CollUtil;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collection;
import java.util.List;

/**
 * ClassName: ItemClientFallback
 * Package: com.hmall.api.client.fallback
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/16 13:58
 * @Version 1.0
 */
@Slf4j
public class ItemClientFallbackFactory implements FallbackFactory<ItemClient> {

    @Override
    public ItemClient create(Throwable cause) {
        return new ItemClient() {
            @Override
            public List<ItemDTO> queryItemsByIds(Collection<Long> ids) {
                log.error("查询商品信息失败", cause);
                return CollUtils.emptyList();
            }

            @Override
            public void deductStock(List<OrderDetailDTO> items) {
                log.error("扣减库存失败", cause);
                throw new RuntimeException(cause);
            }

            @Override
            public void recoverStock(List<OrderDetailDTO> items) {
                log.error("恢复库存失败", cause);
                throw new RuntimeException(cause);
            }
        };
    }
}
