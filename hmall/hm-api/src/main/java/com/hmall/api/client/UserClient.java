package com.hmall.api.client;

import com.hmall.api.dto.DeductMoneyDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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
    @PostMapping("/users/money/deduct")
    public void deductMoney(@RequestBody DeductMoneyDTO deductMoneyDTO);
}