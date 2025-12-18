package com.hmall.cart.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ClassName: CartProperties
 * Package: com.hmall.cart.config
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/11 20:04
 * @Version 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "hm.cart")
public class CartProperties {
    private Integer maxItems;
}
