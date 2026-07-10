package com.hmall.cart.mq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmall.cart.domain.dto.CartSyncMessage;
import com.hmall.cart.domain.po.Cart;
import com.hmall.cart.mapper.CartMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 购物车同步消息消费者：接收 MQ 消息后异步写入 MySQL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartSyncReceiver {

    private final CartMapper cartMapper;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cart.sync.queue", durable = "true"),
            exchange = @Exchange(name = "cart.sync.topic", type = "topic"),
            key = "cart.sync"
    ))
    public void onCartSync(CartSyncMessage message) {
        try {
            upsertCartItem(message);
            log.debug("购物车同步 MySQL 成功，userId={}, itemId={}, version={}",
                    message.getUserId(), message.getItemId(), message.getVersion());
        } catch (Exception e) {
            log.error("购物车同步 MySQL 失败，userId={}, itemId={}",
                    message.getUserId(), message.getItemId(), e);
            // 不抛异常，避免 MQ 无限重试；由补偿任务兜底
        }
    }

    private void upsertCartItem(CartSyncMessage msg) {
        // 查询是否已存在（userId + itemId）
        Cart existing = cartMapper.selectOne(new QueryWrapper<Cart>()
                .lambda()
                .eq(Cart::getUserId, msg.getUserId())
                .eq(Cart::getItemId, msg.getItemId()));

        if (existing != null) {
            // 更新数量和版本
            existing.setNum(msg.getNum());
            existing.setVersion(msg.getVersion());
            cartMapper.updateById(existing);
        } else {
            // 新增
            Cart cart = new Cart();
            cart.setUserId(msg.getUserId());
            cart.setItemId(msg.getItemId());
            cart.setName(msg.getName());
            cart.setSpec(msg.getSpec());
            cart.setPrice(msg.getPrice());
            cart.setImage(msg.getImage());
            cart.setNum(msg.getNum());
            cart.setVersion(msg.getVersion());
            cartMapper.insert(cart);
        }
    }
}
