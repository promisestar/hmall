package com.hmall.common.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Redis 异常隔离切面：Redis 不可用时自动降级返回 null，防止业务崩溃。
 * 业务层在收到 null 时应回退到 MySQL 查询。
 */
@Aspect
@Order(2)
@Configuration
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
public class RedisCacheAspect {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheAspect.class);

    @Around("execution(* com.hmall.common.service.RedisService.*(..))")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            log.error("Redis 操作异常，降级处理 - method: {}", joinPoint.getSignature().toShortString(), e);
            return null;
        }
    }
}
