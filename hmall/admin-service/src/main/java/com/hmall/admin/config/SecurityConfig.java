package com.hmall.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.rsa.crypto.KeyStoreKeyFactory;

import java.security.KeyPair;

/**
 * 安全配置：BCrypt 密码编码器 + admin JWT KeyPair
 */
@Configuration
@EnableConfigurationProperties(AdminJwtProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public KeyPair adminKeyPair(AdminJwtProperties properties) {
        KeyStoreKeyFactory keyStoreKeyFactory = new KeyStoreKeyFactory(
                properties.getLocation(),
                properties.getPassword().toCharArray());
        return keyStoreKeyFactory.getKeyPair(
                properties.getAlias(),
                properties.getPassword().toCharArray());
    }
}
