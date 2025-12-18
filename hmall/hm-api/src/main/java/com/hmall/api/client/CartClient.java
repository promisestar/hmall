package com.hmall.api.client;


import feign.Client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;

/**
 * ClassName: cartClient
 * Package: com.hmall.api.client
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/7 16:06
 * @Version 1.0
 */
@FeignClient(value = "cart-service")
public interface CartClient {
    @DeleteMapping("/carts")
    public void deleteCartItemByIds(@RequestParam("ids") Collection<Long> ids);
}
