package com.hmall.admin.config;

import com.hmall.admin.interceptor.AdminAuthInterceptor;
import com.hmall.admin.security.AdminJwtTool;
import com.hmall.admin.security.DynamicSecurityService;
import com.hmall.admin.service.IResourceService;
import com.hmall.common.service.RedisService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * admin 拦截器和动态权限配置
 */
@Configuration
public class AdminInterceptorConfig {

    @Bean
    public DynamicSecurityService dynamicSecurityService(IResourceService resourceService) {
        return () -> {
            Map<String, String> urlMap = resourceService.getResourceUrlMap();
            return urlMap;
        };
    }

    @Bean
    public AdminAuthInterceptor adminAuthInterceptor(
            AdminJwtTool adminJwtTool,
            AdminAuthProperties authProperties,
            DynamicSecurityService dynamicSecurityService,
            RedisService redisService) {
        return new AdminAuthInterceptor(adminJwtTool, authProperties, dynamicSecurityService, redisService);
    }
}
