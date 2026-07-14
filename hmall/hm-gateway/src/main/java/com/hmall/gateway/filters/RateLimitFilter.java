package com.hmall.gateway.filters;

import com.hmall.common.utils.RateLimitUtil;
import com.hmall.gateway.config.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关滑动窗口限流全局过滤器
 * <p>
 * 在 {@link AuthGlobalFilter}（order=0）之后执行（order=1），
 * 从 user-info 请求头读取已认证的 userId 作为限流维度。
 * <p>
 * 限流规则通过 {@code hm.ratelimit.rules} 配置，支持 Ant 路径匹配。
 * 超限返回 429 Too Many Requests + JSON 错误体。
 * <p>
 * Redis 不可用时（RateLimitUtil 为 null 或降级返回 true）放行请求，
 * 依赖后端 Lua 预减和 MySQL 行锁兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimitProperties rateLimitProperties;

    private final AntPathMatcher matcher = new AntPathMatcher();

    /**
     * RateLimitUtil 非必需依赖（网关没有 Redis 时也能正常工作）
     */
    @Autowired(required = false)
    private RateLimitUtil rateLimitUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 未启用限流或 RateLimitUtil 不可用 → 直接放行
        if (!rateLimitProperties.isEnabled() || rateLimitUtil == null) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().toString();

        // 2. 查找匹配的限流规则
        RateLimitProperties.Rule matchedRule = findMatchedRule(path);
        if (matchedRule == null) {
            return chain.filter(exchange);
        }

        // 3. 从 user-info 头获取 userId（由 AuthGlobalFilter 设置）
        String userInfo = request.getHeaders().getFirst("user-info");
        if (userInfo == null || userInfo.isEmpty()) {
            // 未认证的请求不限流（由 AuthGlobalFilter 拦截），直接放行
            return chain.filter(exchange);
        }

        // 4. 构建限流 key 并检查
        String rateLimitKey = "ratelimit:" + path + ":" + userInfo;
        boolean allowed = rateLimitUtil.allowRequest(
                rateLimitKey,
                matchedRule.getMaxRequests(),
                matchedRule.getWindowMs()
        );

        if (!allowed) {
            log.info("限流拒绝 userId={}, path={}, key={}", userInfo, path, rateLimitKey);
            return reject(exchange);
        }

        return chain.filter(exchange);
    }

    /**
     * 查找第一个匹配当前路径的限流规则
     */
    private RateLimitProperties.Rule findMatchedRule(String path) {
        for (RateLimitProperties.Rule rule : rateLimitProperties.getRules()) {
            for (String pattern : rule.getPaths()) {
                if (matcher.match(pattern, path)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 返回 429 Too Many Requests
     */
    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"message\":\"请求过于频繁，请稍后再试\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 在 AuthGlobalFilter (order=0) 之后执行，确保可从 user-info 头获取 userId
        return 1;
    }
}
