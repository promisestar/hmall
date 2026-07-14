package com.hmall.trade.constants;

/**
 * ClassName: MQConstants
 * Package: com.hmall.trade.constants
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/26 20:30
 * @Version 1.0
 */
public interface MQConstants {

    String DELAY_EXCHANGE_NAME = "trade.delay.direct";

    String DELAY_ORDER_QUEUE_NAME = "trade.delay.order.queue";

    String DELAY_ORDER_KEY = "delay.order";

    String CLEAR_CART_EXCHANGE_NAME = "trade.topic";

    String CLEAR_CART_QUEUE_NAME = "clear.cart.queue";

    String CLEAR_CART_KEY = "order.create";

    // ==================== 秒杀 MQ 常量 ====================

    String SECKILL_EXCHANGE_NAME = "seckill.topic";

    String SECKILL_ORDER_QUEUE_NAME = "seckill.order.queue";

    String SECKILL_ORDER_KEY = "seckill.order";
}
