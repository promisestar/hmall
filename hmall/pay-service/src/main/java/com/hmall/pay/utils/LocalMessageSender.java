package com.hmall.pay.utils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmall.pay.domain.po.LocalMessage;
import com.hmall.pay.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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

    // 每10秒扫描一次待发送消息
    @Scheduled(fixedDelay = 10_000) // 定时任务
    public void sendPendingMessages() {
        List<LocalMessage> pendingMessages = localMessageMapper.selectList(
                new LambdaQueryWrapper<LocalMessage>()
                        .eq(LocalMessage::getStatus, 0)
                        .or()
                        .eq(LocalMessage::getStatus, 2) // 重试失败的
                        .last("LIMIT 100")
        );

        for (LocalMessage msg : pendingMessages) {
            try {
                rabbitTemplate.convertAndSend(
                        msg.getExchange(),
                        msg.getRoutingKey(),
                        msg.getMessageBody()
                );
                // 标记成功
                msg.setStatus(1);
                msg.setUpdateTime(LocalDateTime.now());
                localMessageMapper.updateById(msg);
                log.info("消息发送成功，messageId={}", msg.getMessageId());
            } catch (Exception e) {
                // 重试次数限制（例如最多5次）
                int newTryCount = msg.getTryCount() + 1;
                if (newTryCount >= 5) {
                    msg.setStatus(2); // 永久失败
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