package com.hmall.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * admin 权限白名单配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "hm.admin.auth")
public class AdminAuthProperties {
    private List<String> excludePaths = new ArrayList<>();
}
