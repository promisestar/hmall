package com.hmall.trade.utils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.hmall.trade.domain.po.LocalMessage;
import com.hmall.trade.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ClassName: LocalMessageSender
 * Package: com.hmall.pay.utils
 * Description:
 *
 * @Author Raiden
 * @Create 2025/12/30 11:27
 * @Version 1.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LocalMessageSender {

    private final LocalMessageMapper localMessageMapper;
    private final RabbitTemplate rabbitTemplate;
    // 注入JSON序列化器（和RabbitTemplateConfig中一致）
    private final Jackson2JsonMessageConverter messageConverter;

    @Scheduled(fixedDelay = 10_000)
    public void sendPendingMessages() {
        List<LocalMessage> pendingMessages = localMessageMapper.selectList(
                new LambdaQueryWrapper<LocalMessage>()
                        .eq(LocalMessage::getStatus, 0)
                        .or()
                        .eq(LocalMessage::getStatus, 2)
                        .last("LIMIT 100")
        );

        for (LocalMessage msg : pendingMessages) {
            try {
                // 1. 构建消息体（序列化消息内容）
                Object messageBody = msg.getMessageBody();
                MessageProperties properties = new MessageProperties();
                // 2. 手动注入用户ID到消息头（从LocalMessage中读取）
                if (msg.getUserId() != null) {
                    properties.setHeader("USER-ID", msg.getUserId());
                }
                // 3. 序列化消息体为AMQP Message对象
                Message amqpMessage = messageConverter.toMessage(messageBody, properties);

                // 4. 发送消息（使用send方法，而非convertAndSend）
                rabbitTemplate.send(msg.getExchange(), msg.getRoutingKey(), amqpMessage);

                // 标记成功
                msg.setStatus(1);
                msg.setUpdateTime(LocalDateTime.now());
                localMessageMapper.updateById(msg);
                log.info("消息发送成功，messageId={}", msg.getMessageId());
            } catch (Exception e) {
                int newTryCount = msg.getTryCount() + 1;
                if (newTryCount >= 5) {
                    msg.setStatus(2);
                    log.error("消息发送失败超过5次，messageId={}", msg.getMessageId(), e);
                } else {
                    msg.setTryCount(newTryCount);
                    log.warn("消息发送失败，重试次数={}，messageId={}", newTryCount, msg.getMessageId(), e);
                }
                msg.setUpdateTime(LocalDateTime.now());
                localMessageMapper.updateById(msg);
            }
        }
    }
}