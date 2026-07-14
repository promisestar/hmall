package com.hmall.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 限流配置属性
 * <p>
 * 在 application.yml 中通过 {@code hm.ratelimit} 前缀配置：
 * <pre>
 * hm:
 *   ratelimit:
 *     enabled: true
 *     rules:
 *       - paths: ["/seckill/**"]
 *         max-requests: 1
 *         window-ms: 5000
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "hm.ratelimit")
public class RateLimitProperties {

    /**
     * 是否启用限流
     */
    private boolean enabled = true;

    /**
     * 限流规则列表
     */
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {

        /**
         * 需要限流的路径模式（Ant 风格，如 "/seckill/**"）
         */
        private List<String> paths = new ArrayList<>();

        /**
         * 窗口内最大请求数（默认 1）
         */
        private int maxRequests = 1;

        /**
         * 窗口大小（毫秒，默认 5000 = 5秒）
         */
        private long windowMs = 5000;
    }
}
