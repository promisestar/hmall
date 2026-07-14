package com.hmall.admin.feign.fallback;

import com.hmall.admin.feign.TradeFeignClient;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class TradeFeignFallbackFactory implements FallbackFactory<TradeFeignClient> {
    @Override
    public TradeFeignClient create(Throwable cause) {
        log.error("调用 trade-service 失败", cause);
        return new TradeFeignClient() {
            @Override
            public PageDTO<Object> queryOrderByPage(PageQuery pageQuery, Integer status, Long orderId,
                                                      String startTime, String endTime) {
                return new PageDTO<>();
            }

            @Override
            public R<Object> queryOrderById(Long id) {
                return R.error("订单服务暂时不可用");
            }

            @Override
            public R<Void> batchDelivery(List<Long> orderIds) {
                return R.error("订单服务暂时不可用");
            }

            @Override
            public R<Void> batchCloseOrders(List<Long> orderIds) {
                return R.error("订单服务暂时不可用");
            }

            @Override
            public R<Void> updateNote(Long id, String note, Integer status) {
                return R.error("订单服务暂时不可用");
            }
        };
    }
}
