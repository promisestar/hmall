package com.itheima.publisher;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClassName: SpringAmqpTest
 * Package: com.itheima.publisher
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/19 20:13
 * @Version 1.0
 */
@Slf4j
@SpringBootTest
class SpringAmqpTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Test
    void testSendMessage() {
        // 1. 设置要发送到的队列
        String queueName = "simple.queue";
        // 2. 设置消息
        String exchangeMessage = "Hello, rabbitmq!";
        // 3. 发送消息
        rabbitTemplate.convertAndSend(queueName, exchangeMessage);
    }

    @Test
    void testSendMessage2() {
        // 1. 设置要发送到的队列
        String queueName = "work.queue";
        // 2. 设置消息
        for (int i = 0; i < 50; i++) {
            String exchangeMessage = "Hello, rabbitmq: " + i;
            // 3. 发送消息
            rabbitTemplate.convertAndSend(queueName, exchangeMessage);
        }
    }

    @Test
    void testSendMessageToExchange() {
        // 1. 设置要发送到的交换机
        String exchangeName = "hmall.fanout";
        // 2. 设置消息
        String exchangeMessage = "Hello, rabbitmq!";
        // 3. 发送消息
        rabbitTemplate.convertAndSend(exchangeName, null, exchangeMessage);
    }

    @Test
    void testSendMessageToDirectExchange() {
        // 1. 设置要发送到的交换机
        String exchangeName = "hmall.direct";
        // 2. 设置消息
        String exchangeMessage = "蓝色： 鸣潮启动!";
        // 3. 发送消息
        rabbitTemplate.convertAndSend(exchangeName, "blue", exchangeMessage);
    }

    @Test
    void testSendMessageToObjectQueue() {
        // 1. 创建一个Map对象
        Map<String, Object> msg = new HashMap<>();
        msg.put("name", "jack");
        msg.put("age", 21);

        // 2. 设置队列名称
        String queueName = "object.queue";

        rabbitTemplate.convertAndSend(queueName, msg);
    }

    @Test
    public void testConfirmCallback() throws InterruptedException {
        // 0. 创建消息确认回调
        CorrelationData cd = new CorrelationData(UUID.randomUUID().toString());
        cd.getFuture().addCallback(new ListenableFutureCallback<CorrelationData.Confirm>() {
            @Override
            public void onFailure(Throwable ex) {
                log.error("handle message ack fail", ex);
            }

            @Override
            public void onSuccess(CorrelationData.Confirm result) {
                if(result.isAck()){
                    log.debug("发送消息成功，收到 ack！");
                }else{
                    log.error("发送消息失败，收到 nack, reason:{}！", result.getReason());
                }
            }
        });

        // 1. 设置要发送到的交换机
        String exchangeName = "hmall.direct";
        // 2. 设置消息
        String exchangeMessage = "蓝色： 鸣潮启动!";
        // 3. 发送消息
        rabbitTemplate.convertAndSend(exchangeName, "blue22", exchangeMessage, cd);

        Thread.sleep(5000);
    }

    @Test
    public void testSendMessage3(){
        // 1. 创建消息
        Message message = MessageBuilder.withBody("Hello, rabbitmq!".getBytes())
                .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)
                .build();
        // 连续发送一百万条消息
        for (int i = 0; i < 1000000; i++) {
            rabbitTemplate.convertAndSend("simple.queue", message);
        }
    }

    @Test
    public void testSendMessageDelay(){
        // 1. 创建消息
        rabbitTemplate.convertAndSend("delay.direct", "delay", "Hello, rabbitmq!", message -> {
            message.getMessageProperties().setDelay(10000);
            return message;
        });
    }
}