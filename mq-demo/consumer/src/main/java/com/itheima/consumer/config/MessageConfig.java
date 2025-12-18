package com.itheima.consumer.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ClassName: MessageConfig
 * Package: com.itheima.consumer.config
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/20 20:45
 * @Version 1.0
 */
@Configuration
public class MessageConfig {

    @Bean
    public Queue objectQueue(){
        return new Queue("object.queue");
    }
}

