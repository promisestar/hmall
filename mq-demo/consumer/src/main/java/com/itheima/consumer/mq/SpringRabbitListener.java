package com.itheima.consumer.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;

/**
 * ClassName: SpringRabbitListener
 * Package: com.itheima.consumer.mq
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/19 20:18
 * @Version 1.0
 */
@Slf4j
@Component
public class SpringRabbitListener {

    @RabbitListener(queues = "simple.queue")
    public void listenSimpleQueue(Message message) {
        log.info("消费者接收到消息的id为: {}", message.getMessageProperties().getMessageId());
        log.info("消费者接收到的消息为：{}", new String(message.getBody()));
        // throw new RuntimeException("故意抛出一个异常");
    }

    @RabbitListener(queues = "work.queue")
    public void listenWorkQueue1(String message) throws InterruptedException {
        System.out.println("消费者1接收到消息： " + message + "time: " + LocalTime.now());
        Thread.sleep(25);
    }

    @RabbitListener(queues = "work.queue")
    public void listenWorkQueue2(String message) throws InterruptedException {
        System.err.println("消费者2接收到消息： " + message + "time: " + LocalTime.now());
        Thread.sleep(200);
    }

    @RabbitListener(queues = "fanout.queue1")
    public void listenFanoutQueue1(String message) throws InterruptedException {
        System.out.println("消费者1接收到消息： " + message + "time: " + LocalTime.now());
    }

    @RabbitListener(queues = "fanout.queue2")
    public void listenFanoutQueue2(String message) throws InterruptedException {
        System.err.println("消费者2接收到消息： " + message + "time: " + LocalTime.now());
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "direct.queue1"),
            exchange = @Exchange(name = "hmall.direct", type = ExchangeTypes.DIRECT),
            key = {"red", "blue"}
    ))
    public void listenDirectQueue1(String message) throws InterruptedException {
        System.out.println("消费者1接收到消息： " + message + "time: " + LocalTime.now());
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "direct.queue2"),
            exchange = @Exchange(name = "hmall.direct", type = ExchangeTypes.DIRECT),
            key = {"yellow", "red"}
    ))
    public void listenDirectQueue2(String message) throws InterruptedException {
        System.err.println("消费者2接收到消息： " + message + "time: " + LocalTime.now());
    }

    @RabbitListener(queues = "object.queue")
    public void listenObjectQueue(Map<String, Object> message) throws InterruptedException {
        System.out.println("消费者1接收到消息： " + message + "time: " + LocalTime.now());
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "delay.queue",durable = "true"),
            exchange = @Exchange(name = "delay.direct", delayed = "true"),
            key = {"delay"}
    ))
    public void listenDelayMessage(String msg){
        log.info("接收到delay.queue的延迟消息： {}", msg);
    }
}
