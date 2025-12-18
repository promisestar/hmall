package com.itheima.publisher.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * ClassName: MqConfig
 * Package: com.itheima.publisher.config
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/24 20:13
 * @Version 1.0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MqConfig {

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct // 表示在bean创建后执行
    public void init(){
        rabbitTemplate.setReturnsCallback(returns -> {
            log.error("监听到了消息return callback");
            log.debug("exchange: {}", returns.getExchange());
            log.debug("routingKey: {}", returns.getRoutingKey());
            log.debug("message: {}", returns.getMessage());
            log.debug("replyCode: {}", returns.getReplyCode());
            log.debug("replyText: {}", returns.getReplyText());
        });
    }
}
