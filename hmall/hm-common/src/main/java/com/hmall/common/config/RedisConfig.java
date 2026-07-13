package com.hmall.common.config;

import com.hmall.common.service.RedisService;
import com.hmall.common.utils.RedisLockUtil;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类（双 Template 设计）。
 * <p>
 * {@code @AutoConfigureBefore(RedisAutoConfiguration.class)} 确保本配置的自定义
 * {@code redisTemplate}（Jackson 序列化）先于 Spring Boot 内置的默认
 * {@code RedisTemplate<Object, Object>} 注册，避免两个同名 Bean 冲突。
 * 这在 WebFlux 环境（如 Gateway）中尤为关键：WebFlux 的自配置链很短，
 * {@code RedisAutoConfiguration} 处理极早，不加此注解会导致两个 redisTemplate 冲突。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
@AutoConfigureBefore(RedisAutoConfiguration.class)
@Import({RedisService.class, RedisLockUtil.class})
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // Key 使用 String 序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Value 使用 JSON 序列化（不启用 DefaultTyping，安全且省存储）
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }
}
