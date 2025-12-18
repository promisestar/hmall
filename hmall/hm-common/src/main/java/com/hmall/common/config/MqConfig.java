package com.hmall.common.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

/**
 * ClassName: MqConfig
 * Package: com.hmall.common.config
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/20 21:14
 * @Version 1.0
 */
@Configuration
@ConditionalOnClass(RabbitTemplate.class)
public class MqConfig {
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(){
        return new Jackson2JsonMessageConverter();
    }
}
