package com.hmall.gateway.filters;

import com.hmall.common.exception.UnauthorizedException;
import com.hmall.gateway.config.AuthProperties;
import com.hmall.gateway.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * ClassName: AuthGlobalFilter
 * Package: com.hmall.gateway.filters
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/9 16:27
 * @Version 1.0
 */
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTool jwtTool;

    private final AuthProperties authProperties;

    private final AntPathMatcher matcher = new AntPathMatcher();
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 获取请求
        ServerHttpRequest request = exchange.getRequest();
        // 2. 判断是否需要做登录拦截
        if(isExclude(request.getPath().toString())){
            return chain.filter(exchange);
        }
        // 3. 获取token
        String token = null;
        List<String> headers = request.getHeaders().get("authorization");
        if(headers != null && !headers.isEmpty()){
            token = headers.get(0);
        }
        // 4. 校验并解析token
        Long userId = null;
        try{
            userId = jwtTool.parseToken(token);
        }catch(UnauthorizedException e){
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
        // 5. 传递用户信息给微服务
        String userInfo = userId.toString();
        ServerWebExchange swe = exchange.mutate()
                .request(builder -> {
                    builder.header("user-info", userInfo);
                })
                .build();
        // 6. 放行
        return chain.filter(swe);
    }

    private boolean isExclude(String string) {
        for (String excludePath : authProperties.getExcludePaths()) {
            if(matcher.match(excludePath, string)){
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
