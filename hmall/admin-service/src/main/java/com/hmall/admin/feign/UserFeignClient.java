package com.hmall.admin.feign;

import com.hmall.admin.feign.fallback.UserFeignFallbackFactory;
import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "user-service", contextId = "admin-user",
        configuration = DefaultFeignConfig.class,
        fallbackFactory = UserFeignFallbackFactory.class)
public interface UserFeignClient {

    @GetMapping("/users/page")
    PageDTO<Object> queryUserByPage(@SpringQueryMap PageQuery pageQuery,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer status);

    @GetMapping("/users/{id}")
    R<Object> queryUserById(@PathVariable Long id);

    @PostMapping("/users/status/{id}")
    R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status);

    @PostMapping("/users/balance/{id}")
    R<Void> updateBalance(@PathVariable Long id, @RequestParam Integer delta);
}
