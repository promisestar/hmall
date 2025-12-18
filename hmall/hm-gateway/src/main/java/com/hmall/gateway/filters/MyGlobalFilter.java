package com.hmall.gateway.filters;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * ClassName: MyGlobalFilter
 * Package: com.hmall.gateway.filters
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/9 14:25
 * @Version 1.0
 */
@Component
public class MyGlobalFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 获取请求
        ServerHttpRequest request = exchange.getRequest();
        // 2. 过滤器业务处理
        HttpHeaders headers = request.getHeaders();
        // 3. 检查输出的请求头
        System.out.println("headers" + headers);
        // 4. 放行
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
