package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 秒杀下单结果 VO
 */
@Data
@Accessors(chain = true)
public class SeckillResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 状态：success=下单成功, pending=排队中, failed=失败
     */
    private String status;

    /**
     * 提示消息
     */
    private String message;

    /**
     * 订单ID（仅 success 时有值）
     */
    private Long orderId;

    public static SeckillResultVO success(Long orderId) {
        return new SeckillResultVO().setStatus("success").setOrderId(orderId).setMessage("秒杀成功");
    }

    public static SeckillResultVO pending() {
        return new SeckillResultVO().setStatus("pending").setMessage("排队中，请稍候");
    }

    public static SeckillResultVO fail(String message) {
        return new SeckillResultVO().setStatus("failed").setMessage(message);
    }
}
