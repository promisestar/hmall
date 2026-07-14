package com.hmall.admin.feign;

import com.hmall.admin.feign.fallback.TradeFeignFallbackFactory;
import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "trade-service", contextId = "admin-trade",
        configuration = DefaultFeignConfig.class,
        fallbackFactory = TradeFeignFallbackFactory.class)
public interface TradeFeignClient {

    @GetMapping("/orders/admin/page")
    PageDTO<Object> queryOrderByPage(@SpringQueryMap PageQuery pageQuery,
                                      @RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) Long orderId,
                                      @RequestParam(required = false) String startTime,
                                      @RequestParam(required = false) String endTime);

    @GetMapping("/orders/{id}")
    R<Object> queryOrderById(@PathVariable Long id);

    @PostMapping("/orders/batch/delivery")
    R<Void> batchDelivery(@RequestBody List<Long> orderIds);

    @PostMapping("/orders/batch/close")
    R<Void> batchCloseOrders(@RequestBody List<Long> orderIds);

    @PostMapping("/orders/{id}/note")
    R<Void> updateNote(@PathVariable Long id, @RequestParam(required = false) String note,
                       @RequestParam(required = false) Integer status);
}
