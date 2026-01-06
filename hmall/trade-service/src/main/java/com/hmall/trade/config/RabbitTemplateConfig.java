package com.hmall.trade.config;

import com.hmall.common.utils.UserContext;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;


/**
 * ClassName: RabbitTemplateConfig
 * Package: com.hmall.trade.config
 * Description:
 *
 * @Author Raiden
 * @Create 2025/12/31 15:05
 * @Version 1.0
 */
@Configuration
public class RabbitTemplateConfig {
//    @Bean
//    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
//        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
//
//        // 设置消息发送前的处理器：自动注入用户上下文到消息头
//        rabbitTemplate.setBeforePublishPostProcessors(message -> {
//            Long userId = UserContext.getUser();
//            if (userId != null) {
//                // 将用户信息放入消息头（自定义key，如"X-USER-ID"）
//                message.getMessageProperties().setHeader("USER-ID", userId);
//            }
//            return message;
//        });
//        // 配置JSON序列化器（避免类型漂移问题）
//        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
//        return rabbitTemplate;
//    }
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        // 仅保留JSON序列化器配置
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        return rabbitTemplate;
    }

    // 注入JSON序列化器到Spring容器，供LocalMessageSender使用
    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
