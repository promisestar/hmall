package com.hmall.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "hm.jwt")
public class JwtProperties {
    private Resource location;
    private String password;
    private String alias;
    /** token 总有效期 */
    private Duration tokenTTL = Duration.ofMinutes(10);
    /** 续期冷却窗口：距上次签发超过此时间才允许续期，防止高频刷新 */
    private Duration refreshWindow = Duration.ofMinutes(15);
}
