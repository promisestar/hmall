package com.hmall.cart.Listener;

import com.hmall.cart.service.ICartService;
import com.hmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * ClassName: clearCartListener
 * Package: com.hmall.cart.Listener
 * Description:
 *
 * @Author Raiden
 * @Create 2025/12/31 13:45
 * @Version 1.0
 */
@Component
@RequiredArgsConstructor
public class clearCartListener {

    private final ICartService cartService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cart.clear.queue", durable = "true"),
            exchange = @Exchange(name = "trade.topic", type = "topic"),
            key = "order.create"
    ))
    public void listenClearCart(Collection<Long> itemIds, @Header(value = "USER-ID", required = false)Number userIdObj) throws InterruptedException{
        try{
            Long userId = userIdObj != null ? userIdObj.longValue() : null;
            if(userId != null){
                UserContext.setUser(userId);
            }
            if(!itemIds.isEmpty()){
                cartService.removeByItemIds(itemIds);
            }
        } finally {
            UserContext.removeUser();
        }
    }
}
