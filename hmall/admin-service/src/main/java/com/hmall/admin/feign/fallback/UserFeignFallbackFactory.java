package com.hmall.admin.feign.fallback;

import com.hmall.admin.feign.UserFeignClient;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserFeignFallbackFactory implements FallbackFactory<UserFeignClient> {
    @Override
    public UserFeignClient create(Throwable cause) {
        log.error("调用 user-service 失败", cause);
        return new UserFeignClient() {
            @Override
            public PageDTO<Object> queryUserByPage(PageQuery pageQuery, String keyword, Integer status) {
                return new PageDTO<>();
            }

            @Override
            public R<Object> queryUserById(Long id) {
                return R.error("用户服务暂时不可用");
            }

            @Override
            public R<Void> updateStatus(Long id, Integer status) {
                return R.error("用户服务暂时不可用");
            }

            @Override
            public R<Void> updateBalance(Long id, Integer delta) {
                return R.error("用户服务暂时不可用");
            }
        };
    }
}
