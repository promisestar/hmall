package com.hmall.api.client;

import com.hmall.api.client.fallback.ItemClientFallbackFactory;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;

/**
 * ClassName: ItemClient
 * Package: com.hmall.cart.client
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/4 19:47
 * @Version 1.0
 */
@FeignClient(value = "item-service", fallbackFactory = ItemClientFallbackFactory.class)
public interface ItemClient {
    @GetMapping("/items")
    List<ItemDTO> queryItemsByIds(@RequestParam("ids") Collection<Long> ids);
    @PutMapping("/items/stock/deduct")
    public void deductStock(@RequestBody List<OrderDetailDTO> items);
    @PutMapping("/items/stock/recover")
    public void recoverStock(@RequestBody List<OrderDetailDTO> items);
}
