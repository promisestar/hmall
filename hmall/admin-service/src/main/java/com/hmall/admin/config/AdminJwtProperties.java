package com.hmall.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * admin JWT 密钥配置（独立于 C 端 JWT）
 */
@Data
@ConfigurationProperties(prefix = "hm.admin.jwt")
public class AdminJwtProperties {
    private Resource location;
    private String password;
    private String alias;
    /** token 总有效期 */
    private Duration tokenTTL = Duration.ofHours(2);
    /** 续期冷却窗口 */
    private Duration refreshWindow = Duration.ofMinutes(30);
}
