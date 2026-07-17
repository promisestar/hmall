package com.hmall.api.client;

import com.hmall.api.dto.OrderDetailDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;

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

    /**
     * 获取当前用户已购商品 ID 及数量（有效订单：已付款/已发货/已收货/已评价）
     * <p>
     * 用于推荐服务聚合用户偏好，按 itemId 聚合购买数量。
     * userId 通过 DefaultFeignConfig 透传的 user-info header 获取。
     *
     * @return 已购商品列表（itemId + num），无购买记录时返回空列表
     */
    @GetMapping("/orders/purchased-items")
    List<OrderDetailDTO> queryPurchasedItems();
}