package com.hmall.api.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * ClassName: OrderClient
 * Package: com.hmall.api.client
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/7 19:14
 * @Version 1.0
 */
@FeignClient(value = "trade-service")
public interface TradeClient {
    @PutMapping("/orders/{orderId}")
    public void markOrderPaySuccess(@PathVariable("orderId") Long orderId);
}