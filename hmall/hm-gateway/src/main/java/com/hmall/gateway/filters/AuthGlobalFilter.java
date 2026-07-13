package com.hmall.gateway.filters;

import com.hmall.common.exception.UnauthorizedException;
import com.hmall.common.service.RedisService;
import com.hmall.gateway.config.AuthProperties;
import com.hmall.gateway.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关全局认证过滤器
 * <p>
 * 功能：
 * 1. 白名单路径直接放行
 * 2. 从 Authorization 头提取 JWT，解析 userId
 * 3. 校验 token 是否在黑名单中（登出失效）
 * 4. 自动续期：超过冷却窗口则生成新 token 通过响应头下发
 * 5. 将 userId 写入 user-info 头传递给下游微服务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTool jwtTool;

    private final AuthProperties authProperties;

    /**
     * RedisService 非必需依赖（网关没有 Redis 时也能正常工作，只是不检查黑名单）
     */
    @Autowired(required = false)
    private RedisService redisService;

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 获取请求
        ServerHttpRequest request = exchange.getRequest();
        // 2. 判断是否需要做登录拦截
        if (isExclude(request.getPath().toString())) {
            return chain.filter(exchange);
        }
        // 3. 获取 token
        String token = null;
        List<String> headers = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (headers != null && !headers.isEmpty()) {
            token = headers.get(0);
        }
        // 4. 校验并解析 token
        Long userId;
        try {
            userId = jwtTool.parseToken(token);
        } catch (UnauthorizedException e) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        // 5. 检查 token 黑名单（登出失效）
        if (redisService != null) {
            try {
                String jti = jwtTool.getJti(token);
                if (jti != null && redisService.isTokenBlacklisted(jti)) {
                    log.info("token 已登出失效，拒绝访问, jti={}, userId={}", jti, userId);
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return response.setComplete();
                }
            } catch (Exception e) {
                // Redis 检查失败不阻塞请求（降级处理）
                log.warn("Redis 黑名单检查异常，降级放行, userId={}", userId, e);
            }
        }

        // 6. Token 续期：超过冷却窗口则生成新 token
        String newToken = jwtTool.refreshToken(token);
        ServerHttpResponse response = exchange.getResponse();
        if (newToken != null) {
            response.getHeaders().set(HttpHeaders.AUTHORIZATION, newToken);
            log.debug("token 已续期，userId={}", userId);
        }

        // 7. 传递用户信息给微服务
        String userInfo = userId.toString();
        ServerWebExchange swe = exchange.mutate()
                .request(builder -> builder.header("user-info", userInfo))
                .build();
        // 8. 放行
        return chain.filter(swe);
    }

    private boolean isExclude(String path) {
        for (String excludePath : authProperties.getExcludePaths()) {
            if (matcher.match(excludePath, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
