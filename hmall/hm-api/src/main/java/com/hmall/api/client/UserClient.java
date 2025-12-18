package com.hmall.api.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;

/**
 * ClassName: UserClient
 * Package: com.hmall.api.client
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/7 19:11
 * @Version 1.0
 */
@FeignClient(value = "user-service")
public interface UserClient {
    @PutMapping("/users/money/deduct")
    public void deductMoney(@RequestParam("pw") String pw,@RequestParam("amount") Integer amount);
}