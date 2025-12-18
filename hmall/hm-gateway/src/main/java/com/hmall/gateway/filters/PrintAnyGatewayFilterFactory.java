package com.hmall.gateway.filters;

import lombok.Data;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * ClassName: PrintAnyGatewayFilterFactory
 * Package: com.hmall.gateway.filters
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/9 15:55
 * @Version 1.0
 */
@Component
public class PrintAnyGatewayFilterFactory extends AbstractGatewayFilterFactory<PrintAnyGatewayFilterFactory.Config> {

    @Override
    public GatewayFilter apply(Config config) {
        return new OrderedGatewayFilter(new GatewayFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                String a = config.getA();
                String b = config.getB();
                String c = config.getC();
                System.out.println("a: " + a);
                System.out.println("b: " + b);
                System.out.println("c: " + c);
                System.out.println("print any filter running");
                // 放行
                return chain.filter(exchange);
            }
        }, 1);
    }
    @Data
    public static class Config{
        private String a;
        private String b;
        private String c;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("a", "b", "c");
    }
    public PrintAnyGatewayFilterFactory(){
        super(Config.class);
    }
}
